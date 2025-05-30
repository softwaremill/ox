package ox.internal

import ox.discard

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Until the structured concurrency JEP is stable in an LTS release, a replacement for `StructuredTaskScope`. Downside: does not integrate
  * with scoped values. Upside: works with any Java 21+.
  *
  * Naming: `ThreadFlock` is part of the Structured Concurrency JEP; `ThreadGroup` is already part of the JDK. Hence, using "herd."
  */
private[ox] class ThreadHerd(threadFactory: ThreadFactory):
  private val herdOwner = Thread.currentThread()

  private val shutdownInProgress = new AtomicBoolean(false)
  private val threads = ConcurrentHashMap.newKeySet[Thread]()

  def startThread(t: => Unit): Unit =
    assertNotShuttingDown()
    assertOnOwnerOrHerdThread()

    val thread = threadFactory.newThread(() =>
      try t
      finally threads.remove(Thread.currentThread()).discard
    )
    threads.add(thread)

    thread.start()
  end startThread

  /** Interrupts all thread in this heard, and waits for them to complete. If this process is interrupted, the exception is captured and
    * rethrown only after all threads from the herd have completed.
    */
  def interruptAllAndJoinUntilCompleted(): Unit =
    assertOnOwnerThread()

    shutdownInProgress.set(true)

    /*
    If a startThread() is in progress, after the no-shutdown-assertion, the thread that it creates will be discovered
    in the loop below, as the thread that is starting the new thread itself has to be joined; and this can only happen
    after the new thread is added to the `threads` set.
     */

    var interruptedThreads = Set.empty[Thread] // making sure we interrupt each thread only once
    def interruptIfNeeded(t: Thread): Unit = if !interruptedThreads.contains(t) then
      t.interrupt()
      interruptedThreads += t

    var interruptedException: InterruptedException = null

    while !threads.isEmpty do
      threads.forEach(interruptIfNeeded)
      threads.forEach: thread =>
        interruptIfNeeded(thread) // double-checking, as this thread might have been added after the previous forEach
        try thread.join()
        catch
          case e: InterruptedException => // if the thread is not yet done, we'll join it again in the next iteration
            if interruptedException == null then interruptedException = e
            else interruptedException.addSuppressed(e)
    end while

    if interruptedException != null then throw interruptedException
  end interruptAllAndJoinUntilCompleted

  private def assertNotShuttingDown(): Unit =
    if shutdownInProgress.get() then
      throw new IllegalStateException("Scope is shutting down, cannot start new threads or join existing ones.")

  private def assertOnOwnerOrHerdThread(): Unit =
    val current = Thread.currentThread()
    if current != herdOwner && !threads.contains(current) then
      throw new IllegalStateException("Forks can only be started from threads that are part of the scope.")

  private def assertOnOwnerThread(): Unit =
    val current = Thread.currentThread()
    if current != herdOwner then
      throw new IllegalStateException("This operation can only be performed from the thread that created the scope.")
end ThreadHerd
