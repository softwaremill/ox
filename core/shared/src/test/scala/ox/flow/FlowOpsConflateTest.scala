package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.Channel
import ox.channels.ChannelClosedException

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class FlowOpsConflateTest extends AnyFlatSpec with Matchers:

  behavior of "conflate"

  it should "aggregate elements when downstream is busy" in supervised:
    val upstream = Channel.rendezvous[Int]
    val downstreamBlocked = CountDownLatch(1)
    val canProceed = CountDownLatch(1)

    forkDiscard:
      upstream.send(1)
      downstreamBlocked.await()
      for i <- 2 to 10 do upstream.send(i)
      upstream.done()
      canProceed.countDown()

    val isFirst = AtomicBoolean(true)
    val result = Flow
      .fromSource(upstream)
      .conflate[Int](_ + _)
      .tap: _ =>
        if isFirst.compareAndSet(true, false) then
          downstreamBlocked.countDown()
          canProceed.await()
      .runToList()

    // Element 1 passes through, remaining elements 2-10 are conflated while downstream is busy
    result shouldBe List(1, (2 to 10).sum)

  it should "pass through elements when downstream is ready and upstream is slow" in supervised:
    val upstream = Channel.rendezvous[Int]
    val consumed = Semaphore(0)

    forkDiscard:
      upstream.send(1)
      for i <- 2 to 3 do
        consumed.acquire()
        upstream.send(i)
      upstream.done()

    val result = Flow
      .fromSource(upstream)
      .conflate[Int](_ + _)
      .tap(_ => consumed.release())
      .runToList()

    result shouldBe List(1, 2, 3)

  it should "handle empty flow" in:
    Flow.empty[Int].conflate[Int](_ + _).runToList() shouldBe Nil

  it should "propagate errors" in:
    intercept[ChannelClosedException.Error]:
      Flow
        .fromValues(1, 2)
        .concat(Flow.failed(new IllegalStateException("boom")))
        .conflate[Int](_ + _)
        .runToList()
    .getCause() shouldBe a[IllegalStateException]

  behavior of "conflateWithSeed"

  it should "aggregate with custom seed when downstream is busy" in supervised:
    val upstream = Channel.rendezvous[Int]
    val downstreamBlocked = CountDownLatch(1)
    val canProceed = CountDownLatch(1)

    forkDiscard:
      upstream.send(1)
      downstreamBlocked.await()
      for i <- 2 to 5 do upstream.send(i)
      upstream.done()
      canProceed.countDown()

    val isFirst = AtomicBoolean(true)
    val result = Flow
      .fromSource(upstream)
      .conflateWithSeed(Set(_))((acc, x) => acc + x)
      .tap: _ =>
        if isFirst.compareAndSet(true, false) then
          downstreamBlocked.countDown()
          canProceed.await()
      .runToList()

    // Element 1 passes through as Set(1), remaining elements conflated into one set
    result shouldBe List(Set(1), Set(2, 3, 4, 5))

end FlowOpsConflateTest
