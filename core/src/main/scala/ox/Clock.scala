package ox

import ox.channels.Channel
import ox.internal.ThreadHerd

import java.lang.Thread.State
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.concurrent.duration.Duration
import scala.annotation.implicitNotFound
import scala.util.NotGiven

sealed trait Clock:
  def sleep(howLong: FiniteDuration): Unit
  def now: Instant
  def nowMillis: Long
  def nowNanos: Long

  private[ox] def herdStarted(herd: ThreadHerd): Unit
  private[ox] def herdCompleted(herd: ThreadHerd): Unit
end Clock

object Clock extends Clock:
  def current: Clock = currentClock.get()

  override def sleep(howLong: FiniteDuration): Unit = current.sleep(howLong)
  override def now: Instant = current.now
  override def nowMillis: Long = current.nowMillis
  override def nowNanos: Long = current.nowNanos

  override private[ox] def herdStarted(herd: ThreadHerd): Unit = current.herdStarted(herd)
  override private[ox] def herdCompleted(herd: ThreadHerd): Unit = current.herdCompleted(herd)
end Clock

//

private[ox] object SystemClock extends Clock:
  override def sleep(howLong: FiniteDuration): Unit = Thread.sleep(howLong.toMillis)
  override def now: Instant = Instant.now()
  override def nowMillis: Long = System.currentTimeMillis()
  override def nowNanos: Long = System.nanoTime()

  override private[ox] def herdStarted(herd: ThreadHerd): Unit = ()
  override private[ox] def herdCompleted(herd: ThreadHerd): Unit = ()
end SystemClock

private[ox] class MockClock(
    nowRef: AtomicReference[Instant],
    onSleep: MockTimeSignal.ThreadSleep => Unit,
    val herds: java.util.Set[ThreadHerd]
) extends Clock:
  override def sleep(howLong: FiniteDuration): Unit =
    val waiter = new CountDownLatch(1)
    onSleep(MockTimeSignal.ThreadSleep(now.plus(howLong.toMillis, ChronoUnit.MILLIS), () => waiter.countDown()))
    waiter.await()
  override def now: Instant = nowRef.get()
  override def nowMillis: Long = now.toEpochMilli
  override def nowNanos: Long = now.toEpochMilli * 1000000

  override private[ox] def herdStarted(herd: ThreadHerd): Unit = herds.add(herd).discard
  override private[ox] def herdCompleted(herd: ThreadHerd): Unit = herds.remove(herd).discard
end MockClock

//

enum TickResult[T]:
  case Result(result: Try[T])
  case Next(howLong: FiniteDuration)
end TickResult

/** Should only ever be used on a single thread, received by the mockTime callback */
trait ClockTicker[T]:
  /** Run the code until a result is available (the given code block completes with a value or with an exception), or until all threads are
    * waiting.
    *
    * Calling this method after the final result has already been returned returns the result once again (is idempotent).
    */
  def tick(): TickResult[T]

  def advanceTimeAndTick(howLong: FiniteDuration): TickResult[T]

  def tickUntilCompletion(): TickResult.Result[T] =
    @tailrec
    def doTick(lastResult: TickResult[T]): TickResult.Result[T] =
      lastResult match
        case r: TickResult.Result[T]  => r
        case TickResult.Next(howLong) => doTick(advanceTimeAndTick(howLong))
      end match

    doTick(tick())
  end tickUntilCompletion
end ClockTicker

private val currentClock = ForkLocal[Clock](SystemClock)

private enum MockTimeSignal[+T]:
  case CheckThreads() extends MockTimeSignal[Nothing]
  case MainThreadExited(result: Try[T])
  case ThreadSleep(until: Instant, wakeup: () => Unit) extends MockTimeSignal[Nothing]

def mockTime[T, U](f: => T)(run: ClockTicker[T] => U)(using
    @implicitNotFound(
      "mockTime should not be used within a concurrency scope, to avoid starting forks in the outer scope, which would not use the mock clock. " +
        "Instead, concurrency scopes should be started within the mockTime block."
    ) nn: NotGiven[OxUnsupervised]
): U =
  val signals = Channel.unlimited[MockTimeSignal[T]]
  val nowRef = new AtomicReference[Instant](Instant.now())
  val mockClock = new MockClock(nowRef, signals.send, ConcurrentHashMap.newKeySet())

  currentClock.supervisedWhere(mockClock) {
    forkDiscard:
      forever:
        Thread.sleep(1000L)
        signals.send(MockTimeSignal.CheckThreads())

    // we need to check the state of the main thread and all threads in scopes started from this
    // thread - passing the thread using a single-element channel, and blocking until that thread
    // has started.
    val mainThreadChannel = Channel.rendezvous[Thread]
    val waitForFirstTick = new CountDownLatch(1)
    forkDiscard:
      mainThreadChannel.send(Thread.currentThread())
      waitForFirstTick.await()
      signals.send(MockTimeSignal.MainThreadExited(Try(f)))
      signals.done()

    val mainThread = mainThreadChannel.receive()

    given Ordering[MockTimeSignal.ThreadSleep] = Ordering.by(_.until.toEpochMilli())
    val waitingThreads = PriorityQueue.empty[MockTimeSignal.ThreadSleep]

    //

    def threadIsWaiting(thread: Thread): Boolean =
      thread.getState() match
        case State.NEW           => false
        case State.RUNNABLE      => false
        case State.BLOCKED       => true
        case State.WAITING       => true
        case State.TIMED_WAITING => throw new UnsupportedOperationException("Mock time does not support Java-native timed waiting")
        case State.TERMINATED    => false
    end threadIsWaiting

    /*
    
    If all threads are reported as blocked/waiting, then even if the thread statuses aren't accurate, unless the mock-time functionality
    is used incorrectly (with external interactions), then the main code block & derived threads are *guaranteed* to be blocked.

    What might not be accurate, is that thread might not report to be blocked, even though they already are. For these cases, we've
    got the periodic checks.

     */

    def allThreadsWaiting: Boolean =
      import scala.jdk.CollectionConverters.*

      // todo remove
      println("Checking threads")
      (List(mainThread) ++ mockClock.herds.iterator().asScala.flatMap(_.threads.iterator().asScala)).foreach(t =>
        println(s"${t.threadId} is ${t.getState()} (${threadIsWaiting(t)})")
      )

      threadIsWaiting(mainThread) && mockClock.herds.iterator().asScala.flatMap(_.threads.iterator().asScala).forall(threadIsWaiting)
    end allThreadsWaiting

    //

    /*
    After unblocking a thread, we need to drain queue of signals and handle all of them
    When all threads are detected as waiting, we once again drain the queue and handle the signals (some might have arrived in-between)
    Then we can determine the result

    The periodic ticker is needed as due to scheduling, a thread might only suspend on a condition after some time.

    After waking up a thread, its state doesn't have to transition to runnable immediately. So when we determine that all threads
    are waiting, we cannot be sure if that's old data or if the thread is waiting again. So we cannot return reliable information
    on the next tick.

    When we tick, we wait until all threads are waiting, or there's a main-thread result.

     */

    run(
      new ClockTicker[T]:
        var result: Option[TickResult[T]] = None

        private def maybeResult: Option[TickResult[T]] =
          result match
            case Some(result) => Some(result)
            case None if allThreadsWaiting =>
              waitingThreads.headOption match
                case None => None
                case Some(value) =>
                  val now = Clock.now
                  Some(TickResult.Next(Duration(value.until.toEpochMilli() - now.toEpochMilli(), "ms")))
              end match
            case None => None
          end match
        end maybeResult

        @tailrec
        override def tick(): TickResult[T] =
          println("Tick")
          waitForFirstTick.countDown() // start the main thread, if not yet started

          maybeResult match
            case Some(result) => result
            case None =>
              signals.receive() match
                case MockTimeSignal.MainThreadExited(r) =>
                  println("Main thread exited")
                  result = Some(TickResult.Result(r))
                  TickResult.Result(r)
                case ts: MockTimeSignal.ThreadSleep =>
                  println("Thread sleep")
                  waitingThreads.enqueue(ts)
                  // tick complete -> return control to the driver together with closest time span
                  maybeResult match
                    case None    => tick()
                    case Some(r) => r
                case MockTimeSignal.CheckThreads() =>
                  println("Check threads")
                  maybeResult match
                    case None    => tick()
                    case Some(r) => r
              end match
          end match
        end tick

        override def advanceTimeAndTick(howLong: FiniteDuration): TickResult[T] =
          println("Advance time and tick")
          val newNow = nowRef.updateAndGet(now => now.plus(howLong.toMillis, ChronoUnit.MILLIS))

          /*
          does thread state update immediately after unblocking, or only when the thread is re-scheduled?
          this is a major problem: if the thread that's woken up is immediately detected as waiting (since it hasn't yet been transitioned to runnable),
          we'll determine that we can return control back to the user, and wait for the next tick. The interval until the next tick might
          also be incorrectly calculcated (if it's available at all).

          even if after wakeup we waited for confirmation that the thread was runnable, it might have woken up yet another thread,
          for which we might once again not reliably detect that it suspended.

           */
          @tailrec
          def doDequeue(): Unit =
            waitingThreads.headOption match
              case None                                 => () // done
              case Some(ts) if ts.until.isAfter(newNow) => () // not yet
              case Some(ts) =>
                waitingThreads.dequeue().discard
                ts.wakeup()
                doDequeue()
            end match
          end doDequeue

          doDequeue()
          tick()
        end advanceTimeAndTick

        // TODO def tickToCompletion()
    )
  }
end mockTime
