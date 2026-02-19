package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.Channel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class FlowOpsBatchTest extends AnyFlatSpec with Matchers:

  behavior of "batch"

  it should "aggregate by count when downstream is busy" in supervised:
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
      .batch(10L, List(_))(_ :+ _)
      .tap: _ =>
        if isFirst.compareAndSet(true, false) then
          downstreamBlocked.countDown()
          canProceed.await()
      .runToList()

    result shouldBe List(List(1), List(2, 3, 4, 5, 6))

  it should "handle empty flow" in:
    Flow.empty[Int].batch(5L, identity)(_ + _).runToList() shouldBe Nil

  // batch delegates to batchWeighted so most tests are there

end FlowOpsBatchTest
