package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.BufferCapacity
import ox.channels.ChannelClosedException

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore

class FlowOpsExtrapolateTest extends AnyFlatSpec with Matchers:

  behavior of "extrapolate"

  it should "repeat last element when downstream is faster" in supervised:
    val c = BufferCapacity.newChannel[Int]
    forkDiscard:
      c.send(1)
      c.done()

    val result = Flow
      .fromSource(c)
      .extrapolate(n => Iterator.continually(n))
      .take(5)
      .runToList()

    result shouldBe List(1, 1, 1, 1, 1)

  it should "switch to latest upstream element" in supervised:
    val upstream = BufferCapacity.newChannel[Int]
    // Gate: upstream waits until the extrapolated value (10) from element 1 is consumed,
    // ensuring element 1's iterator is fully drained before element 2 arrives.
    val extrapolated1Consumed = CountDownLatch(1)

    forkDiscard:
      upstream.send(1)
      extrapolated1Consumed.await()
      upstream.send(2)
      upstream.done()

    val result = Flow
      .fromSource(upstream)
      .extrapolate(n => Iterator.single(n * 10))
      .tap(n => if n == 10 then extrapolated1Consumed.countDown())
      .runToList()

    // Element 1 emitted, then extrapolated 10 emitted and consumed (triggering gate),
    // then element 2 emitted, then extrapolated 20 emitted, then upstream done.
    result shouldBe List(1, 10, 2, 20)

  it should "emit initial element before upstream" in supervised:
    val upstream = BufferCapacity.newChannel[Int]
    val initialConsumed = CountDownLatch(1)

    forkDiscard:
      initialConsumed.await()
      upstream.send(10)
      upstream.done()

    val result = Flow
      .fromSource(upstream)
      .extrapolate(_ => Iterator.empty, initial = Some(0))
      .tap(_ => initialConsumed.countDown())
      .take(2)
      .runToList()

    result shouldBe List(0, 10)

  it should "pass through elements without initial" in supervised:
    val upstream = BufferCapacity.newChannel[Int]
    // Semaphore gates upstream: each send (after the first) waits for the previous element
    // to be consumed, ensuring the empty extrapolator's iterator is drained first.
    val consumed = Semaphore(0)

    forkDiscard:
      upstream.send(1)
      for i <- 2 to 3 do
        consumed.acquire()
        upstream.send(i)
      upstream.done()

    val result = Flow
      .fromSource(upstream)
      .extrapolate(_ => Iterator.empty)
      .tap(_ => consumed.release())
      .runToList()

    result shouldBe List(1, 2, 3)

  it should "extrapolate each element to multiple values" in supervised:
    val upstream = BufferCapacity.newChannel[Int]
    // Gate: upstream waits until the last extrapolated value (100) from element 1 is consumed,
    // ensuring element 1's full iterator is drained before element 2 arrives.
    val lastExtrapolated1Consumed = CountDownLatch(1)

    forkDiscard:
      upstream.send(1)
      lastExtrapolated1Consumed.await()
      upstream.send(2)
      upstream.done()

    val result = Flow
      .fromSource(upstream)
      .extrapolate(n => Iterator(n * 10, n * 100))
      .tap(n => if n == 100 then lastExtrapolated1Consumed.countDown())
      .runToList()

    // Element 1 emitted, then 10 and 100 from extrapolator; gate opens;
    // element 2 emitted, then 20 and 200 from extrapolator; upstream done.
    result shouldBe List(1, 10, 100, 2, 20, 200)

  it should "handle empty flow without initial" in:
    Flow.empty[Int].extrapolate(_ => Iterator.empty).runToList() shouldBe Nil

  it should "handle empty flow with initial" in:
    val result = Flow
      .empty[Int]
      .extrapolate(_ => Iterator.empty, initial = Some(42))
      .take(1)
      .runToList()

    result shouldBe List(42)

  it should "propagate errors" in:
    intercept[ChannelClosedException.Error]:
      Flow
        .fromValues(1)
        .concat(Flow.failed(new IllegalStateException("boom")))
        .extrapolate(n => Iterator.continually(n))
        .take(100)
        .runToList()
    .getCause() shouldBe a[IllegalStateException]

end FlowOpsExtrapolateTest
