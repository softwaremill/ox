package ox.flow.reactive

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.BufferCapacity
import ox.flow.Flow

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Flow.Publisher
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Random
import scala.util.Try

class FlowFromPublisherDiscardStressTest extends AnyFunSuite with Matchers:
  private given BufferCapacity = BufferCapacity(4)
  private val N = 1000
  private val Iterations = 20

  private def checkExactlyOnce(consume: Flow[Int] => Unit): Unit =
    val publisher = new ThreadedPublisher(N)
    val emitted = new ConcurrentLinkedQueue[Int]()
    val discarded = new ConcurrentLinkedQueue[Int]()

    consume(Flow.fromPublisher(publisher, discarded.add(_).discard).tap(emitted.add(_).discard))
    val sent = publisher.join()

    val handled = emitted.asScala.toList ++ discarded.asScala.toList
    // `sent` has no duplicates, so this also catches elements emitted or discarded twice
    withClue("duplicated:")(handled.diff(sent) shouldBe empty)
    withClue("lost:")(sent.diff(handled) shouldBe empty)
  end checkExactlyOnce

  test("should emit or discard each element exactly once when stopping early"):
    for _ <- 1 to Iterations do
      val k = Random.nextInt(N) + 1
      withClue(s"k = $k"):
        checkExactlyOnce(_.take(k).runDrain())

  test("should emit or discard each element exactly once when the consumer throws"):
    for _ <- 1 to Iterations do
      val failAt = Random.nextInt(N) + 1
      withClue(s"failAt = $failAt"):
        checkExactlyOnce(f => Try(f.map(x => if x == failAt then throw new RuntimeException("boom") else x).runDrain()).discard)

  test("should emit or discard each element exactly once when the consumer is interrupted"):
    for _ <- 1 to Iterations do
      val delay = Random.nextInt(2000).micros
      withClue(s"delay = $delay"):
        checkExactlyOnce: f =>
          supervised:
            val result = forkCancellable(f.runDrain())
            sleep(delay)
            result.cancel().discard
end FlowFromPublisherDiscardStressTest

/** Emits `1..n` from its own thread, respecting demand, then completes. After observing cancellation, sends a few more elements, as
  * allowed by the spec.
  */
private class ThreadedPublisher(n: Int) extends Publisher[Int]:
  private val requested = new AtomicLong(0)
  private val cancelled = new AtomicBoolean(false)
  // only accessed by the publisher thread; read after it's joined
  private val sent = ArrayBuffer[Int]()
  @volatile private var thread: Thread = null

  override def subscribe(s: Subscriber[? >: Int]): Unit =
    thread = new Thread(() =>
      var next = 1
      def send(): Unit =
        s.onNext(next)
        sent += next
        next += 1

      while next <= n && !cancelled.get() do
        if next <= requested.get() then send() else Thread.onSpinWait()
      if cancelled.get() then (1 to 3).foreach(_ => send()) else s.onComplete()
    )
    s.onSubscribe(new Subscription:
      override def request(k: Long): Unit = requested.addAndGet(k).discard
      override def cancel(): Unit = cancelled.set(true))
    thread.start()
  end subscribe

  /** Waits for the publisher thread to finish, and returns the elements that were sent. Must be called after the flow completes. */
  def join(): List[Int] =
    if thread != null then thread.join()
    sent.toList
end ThreadedPublisher
