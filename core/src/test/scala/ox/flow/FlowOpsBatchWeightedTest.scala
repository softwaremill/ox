package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.Channel
import ox.channels.ChannelClosedException

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class FlowOpsBatchWeightedTest extends AnyFlatSpec with Matchers:

  behavior of "batchWeighted"

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
      .batchWeighted(10L, _ => 1L, identity)(_ + _)
      .tap(_ => consumed.release())
      .runToList()

    result shouldBe List(1, 2, 3)

  it should "aggregate elements when downstream is busy" in supervised:
    // Rendezvous upstream ensures sends block until batchWeighted receives each element,
    // so canProceed fires only after all elements have been received and aggregated.
    val upstream = Channel.rendezvous[Int]
    val downstreamBlocked = CountDownLatch(1)
    val canProceed = CountDownLatch(1)

    forkDiscard:
      upstream.send(1)
      downstreamBlocked.await()
      for i <- 2 to 6 do upstream.send(i)
      upstream.done()
      canProceed.countDown()

    val isFirst = AtomicBoolean(true)
    val result = Flow
      .fromSource(upstream)
      .batchWeighted(10L, _ => 1L, List(_))(_ :+ _)
      .tap: _ =>
        if isFirst.compareAndSet(true, false) then
          downstreamBlocked.countDown()
          canProceed.await()
      .runToList()

    // Element 1 passes through, remaining elements batch while downstream is busy
    result shouldBe List(List(1), List(2, 3, 4, 5, 6))

  it should "create maximal batch respecting cost function" in supervised:
    val upstream = Channel.rendezvous[Int]
    val downstreamBlocked = CountDownLatch(1)
    val canProceed = CountDownLatch(1)

    forkDiscard:
      upstream.send(1)
      downstreamBlocked.await()
      // Costs: 2+4=6=maxWeight (fills batch), then 5 exceeds remaining budget (0), triggering flush
      for i <- List(2, 4, 5) do upstream.send(i)
      upstream.done()
      canProceed.countDown()

    val isFirst = AtomicBoolean(true)
    val result = Flow
      .fromSource(upstream)
      .batchWeighted(6L, _.toLong, List(_))(_ :+ _)
      .tap: _ =>
        if isFirst.compareAndSet(true, false) then
          downstreamBlocked.countDown()
          canProceed.await()
      .runToList()

    // Element 1 passes through; 2+4=6=maxWeight creates a maximal batch; 5 starts a new batch
    result shouldBe List(List(1), List(2, 4), List(5))

  it should "start new batch when every element exceeds max weight" in supervised:
    val upstream = Channel.rendezvous[Int]
    forkDiscard:
      for i <- List(10, 20, 30) do upstream.send(i)
      upstream.done()

    val result = Flow
      .fromSource(upstream)
      .batchWeighted(3L, _ => 5L, List(_))(_ :+ _)
      .runToList()

    // Each element costs 5, exceeding maxWeight=3, so each is its own batch via seed
    result shouldBe List(List(10), List(20), List(30))

  it should "handle empty flow" in:
    Flow.empty[Int].batchWeighted(10L, _ => 1L, identity)(_ + _).runToList() shouldBe Nil

  it should "propagate errors from upstream" in:
    intercept[ChannelClosedException.Error]:
      Flow
        .fromValues(1, 2, 3)
        .concat(Flow.failed(new IllegalStateException("boom")))
        .batchWeighted(10L, _ => 1L, identity)(_ + _)
        .runToList()
    .getCause() shouldBe a[IllegalStateException]

  it should "propagate errors from costFn" in:
    intercept[ChannelClosedException.Error]:
      Flow
        .fromValues(1, 2, 0, 4)
        .batchWeighted(10L, n => 10 / n, identity)(_ + _)
        .runToList()
    .getCause() shouldBe a[ArithmeticException]

  it should "propagate errors from seed" in:
    intercept[ChannelClosedException.Error]:
      Flow
        .fromValues(1, 2, 3)
        .batchWeighted(10L, _ => 1L, (n: Int) => if n == 1 then throw new IllegalStateException("seed") else n)(_ + _)
        .runToList()
    .getCause() shouldBe a[IllegalStateException]

  it should "propagate errors from aggregate" in supervised:
    val upstream = Channel.rendezvous[Int]
    val downstreamBlocked = CountDownLatch(1)
    val canProceed = CountDownLatch(1)

    forkDiscard:
      upstream.send(1)
      downstreamBlocked.await()
      // Send 2 elements: first is seeded, second triggers aggregate which throws
      try
        upstream.send(2)
        upstream.send(3)
        upstream.done()
      catch case _: Exception => ()
      canProceed.countDown()

    val isFirst = AtomicBoolean(true)
    intercept[ChannelClosedException.Error]:
      Flow
        .fromSource(upstream)
        .batchWeighted(10L, _ => 1L, identity)((_, _) => throw new IllegalStateException("agg"))
        .tap: _ =>
          if isFirst.compareAndSet(true, false) then
            downstreamBlocked.countDown()
            canProceed.await()
        .runToList()
    .getCause() shouldBe a[IllegalStateException]

end FlowOpsBatchWeightedTest
