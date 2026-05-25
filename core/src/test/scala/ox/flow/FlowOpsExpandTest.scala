package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.BufferCapacity
import ox.channels.Channel
import ox.channels.ChannelClosedException

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore

class FlowOpsExpandTest extends AnyFlatSpec with Matchers:

  behavior of "expand"

  it should "emit from infinite expander when downstream is faster" in supervised:
    val c = BufferCapacity.newChannel[Int]
    forkDiscard:
      c.send(1)
      c.done()

    val result = Flow
      .fromSource(c)
      .expand(n => Iterator.continually(n))
      .take(5)
      .runToList()

    // After upstream sends 1 and completes, expand drains the infinite iterator
    result shouldBe List(1, 1, 1, 1, 1)

  it should "drain remaining iterator after upstream completes" in supervised:
    val c = BufferCapacity.newChannel[Int]
    forkDiscard:
      c.send(1)
      c.done()

    val result = Flow
      .fromSource(c)
      .expand(n => Iterator(n * 10, n * 100, n * 1000))
      .runToList()

    result shouldBe List(10, 100, 1000)

  it should "replace iterator when new upstream element arrives" in supervised:
    val upstream = Channel.rendezvous[Int]
    val firstExpanded = CountDownLatch(1)
    val element2Sent = CountDownLatch(1)

    forkDiscard:
      upstream.send(1)
      firstExpanded.await()
      upstream.send(2)
      element2Sent.countDown()
      upstream.done()

    val result = Flow
      .fromSource(upstream)
      .expand(n => Iterator(n * 10, n * 100, n * 1000))
      .tap: n =>
        if n == 10 then
          firstExpanded.countDown()
          element2Sent.await()
      .runToList()

    // 10 is emitted first. While the consumer processes it (blocked in tap until element 2
    // is sent), expand's select can only complete via c.receive — output.send is blocked
    // because the consumer is busy. So 100 is always discarded when element 2 arrives.
    result shouldBe List(10, 20, 200, 2000)

  it should "emit from single-element expander with gated upstream" in supervised:
    val upstream = BufferCapacity.newChannel[Int]
    // Semaphore gates upstream: each send (after the first) waits for the previous
    // iterator to be fully consumed, preventing receive-first bias from discarding elements.
    val consumed = Semaphore(0)

    forkDiscard:
      upstream.send(1)
      for i <- 2 to 3 do
        consumed.acquire()
        upstream.send(i)
      upstream.done()

    val result = Flow
      .fromSource(upstream)
      .expand(n => Iterator.single(n * 10))
      .tap(_ => consumed.release())
      .runToList()

    result shouldBe List(10, 20, 30)

  it should "emit from multi-element expander with gated upstream" in supervised:
    val upstream = BufferCapacity.newChannel[Int]
    // Gate: upstream waits until the last element (100) from element 1's iterator is consumed,
    // ensuring the full iterator is drained before element 2 arrives.
    val lastFromFirst = CountDownLatch(1)

    forkDiscard:
      upstream.send(1)
      lastFromFirst.await()
      upstream.send(2)
      upstream.done()

    val result = Flow
      .fromSource(upstream)
      .expand(n => Iterator(n * 10, n * 100))
      .tap(n => if n == 100 then lastFromFirst.countDown())
      .runToList()

    result shouldBe List(10, 100, 20, 200)

  it should "handle empty flow" in:
    Flow.empty[Int].expand(n => Iterator(n, n)).runToList() shouldBe Nil

  it should "propagate errors from upstream" in:
    intercept[ChannelClosedException.Error]:
      Flow
        .fromValues(1)
        .concat(Flow.failed(new IllegalStateException("boom")))
        .expand(n => Iterator.continually(n))
        .take(100)
        .runToList()
    .getCause() shouldBe a[IllegalStateException]

  it should "propagate errors from expander" in:
    intercept[ChannelClosedException.Error]:
      Flow
        .fromValues(1, 2, 3)
        .expand(_ => throw new IllegalStateException("expander"))
        .runToList()
    .getCause() shouldBe a[IllegalStateException]

  it should "propagate errors from iterator" in:
    intercept[ChannelClosedException.Error]:
      Flow
        .fromValues(1)
        .expand(_ =>
          new Iterator[Int]:
            def hasNext = true
            def next() = throw new IllegalStateException("iterator")
        )
        .take(5)
        .runToList()
    .getCause() shouldBe a[IllegalStateException]

end FlowOpsExpandTest
