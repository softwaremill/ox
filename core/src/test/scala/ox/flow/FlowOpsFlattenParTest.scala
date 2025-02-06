package ox.flow

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import scala.concurrent.duration.DurationInt

import java.util.concurrent.CountDownLatch
import scala.collection.mutable.ListBuffer
import ox.channels.Channel
import ox.channels.BufferCapacity
import ox.channels.ChannelClosed
import ox.channels.ChannelClosedException

class FlowOpsFlattenParTest extends AnyFlatSpec with Matchers with OptionValues:

  behavior of "flattenPar"

  it should "pipe all elements of the child flows into the output flow" in:
    val flow = Flow.fromValues(
      Flow.fromValues(10),
      Flow.fromValues(20, 30),
      Flow.fromValues(40, 50, 60)
    )
    flow.flattenPar(10).runToList() should contain theSameElementsAs List(10, 20, 30, 40, 50, 60)

  it should "handle empty flow" in:
    val flow = Flow.empty[Flow[Int]]
    flow.flattenPar(10).runToList() should contain theSameElementsAs Nil

  it should "handle singleton flow" in:
    val flow = Flow.fromValues(Flow.fromValues(10))
    flow.flattenPar(10).runToList() should contain theSameElementsAs List(10)

  it should "not flatten nested flows" in:
    val flow = Flow.fromValues(Flow.fromValues(Flow.fromValues(10)))
    flow.flattenPar(10).runToList().map(_.runToList()) should contain theSameElementsAs List(List(10))

  it should "handle subsequent flatten calls" in:
    val flow = Flow.fromValues(Flow.fromValues(Flow.fromValues(10), Flow.fromValues(20)))
    flow.flattenPar(10).runToList().flatMap(_.runToList()) should contain theSameElementsAs List(10, 20)

  it should "run at most parallelism child flows" in:
    val flow = Flow.fromValues(
      Flow.timeout(200.millis).concat(Flow.fromValues(10)),
      Flow.timeout(100.millis).concat(Flow.fromValues(20, 30)),
      Flow.fromValues(40, 50, 60)
    )
    // only one flow can run at a time
    flow.flattenPar(1).runToList() should contain theSameElementsAs List(10, 20, 30, 40, 50, 60)
    // when parallelism is increased, all flows are run concurrently
    flow.flattenPar(3).runToList() should contain theSameElementsAs List(40, 50, 60, 20, 30, 10)

  it should "pipe elements realtime" in:
    supervised:
      val source = Channel.bufferedDefault[Flow[Int]]
      val lockA = CountDownLatch(1)
      val lockB = CountDownLatch(1)
      source.send(Flow.fromValues(10))
      source.send:
        val subSource = Channel.bufferedDefault[Int]
        subSource.send(20)
        forkDiscard:
          lockA.await() // 30 won't be added until, lockA is released after 20 consumption
          subSource.send(30)
          subSource.done()
        Flow.fromSource(subSource)

      forkDiscard:
        lockB.await() // 40 won't be added until, lockB is released after 30 consumption
        source.send(Flow.fromValues(40))
        source.done()

      val collected = ListBuffer[Int]()
      Flow
        .fromSource(source)
        .flattenPar(10)
        .runForeach: e =>
          collected += e
          if e == 20 then lockA.countDown()
          else if e == 30 then lockB.countDown()

      collected should contain theSameElementsAs List(10, 20, 30, 40)

  it should "propagate error of any of the child flows and stop piping" in:
    supervised:
      val child1 = Channel.rendezvous[Int]
      val lock = CountDownLatch(1)
      forkDiscard:
        lock.await() // wait for the error to be discovered
        child1.send(10) // `flattenPar` will not receive this, as it will be short-circuited by the error
        child1.doneOrClosed()
      val child2 = Channel.rendezvous[Int]
      forkDiscard:
        child2.error(new IllegalStateException())

      val flow = Flow.fromValues(Flow.fromSource(child1), Flow.fromSource(child2))

      val flattenedFlow =
        implicit val capacity: BufferCapacity = BufferCapacity(0)
        flow.flattenPar(10)

      intercept[ChannelClosedException.Error] {
        flattenedFlow.runToList()
      }.getCause().getCause() shouldBe a[IllegalStateException]

      // no values should be piped by the flattening process after the error
      lock.countDown()
      child1.receive() shouldBe 10
      child1.receiveOrClosed() shouldBe ChannelClosed.Done

  it should "propagate error of the parent flow and stop piping" in:
    supervised:
      val child1 = Channel.rendezvous[Int]
      val lockA = CountDownLatch(1)
      val lockB = CountDownLatch(1)
      forkDiscard:
        child1.send(10)
        lockB.countDown()
        lockA.await()

        child1.send(20)
        child1.done()

      val flow = Flow.usingEmit[Flow[Int]]: sink =>
        sink(Flow.fromSource(child1))
        lockB.await()
        throw new IllegalStateException()

      val flattenedSource =
        implicit val capacity: BufferCapacity = BufferCapacity(0)
        flow.flattenPar(10).runToChannel()

      end flattenedSource

      flattenedSource.receive() shouldBe 10
      flattenedSource.receiveOrClosed() should be(a[ChannelClosed.Error])
      lockA.countDown()

      child1.receive() shouldBe 20
      child1.receiveOrClosed() shouldBe ChannelClosed.Done
end FlowOpsFlattenParTest
