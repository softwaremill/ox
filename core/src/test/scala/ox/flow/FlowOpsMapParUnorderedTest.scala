package ox.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import scala.concurrent.duration.*

import java.util.concurrent.atomic.AtomicInteger
import ox.channels.ChannelClosed

class FlowOpsMapParUnorderedTest extends AnyFlatSpec with Matchers with Eventually:

  behavior of "mapParUnordered"

  for parallelism <- 1 to 10 do
    it should s"map over a source with parallelism limit $parallelism" in supervised:
      // given
      val flow = Flow.fromIterable(1 to 10)
      val running = new AtomicInteger(0)
      val maxRunning = new AtomicInteger(0)

      def f(i: Int) =
        running.incrementAndGet()
        try
          sleep(100.millis)
          i * 2
        finally running.decrementAndGet().discard

      // update max running
      fork:
        var max = 0
        forever:
          max = math.max(max, running.get())
          maxRunning.set(max)
          sleep(10.millis)

      // when
      val result = flow.mapParUnordered(parallelism)(f).runToList()

      // then
      result should contain theSameElementsAs List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
      maxRunning.get() shouldBe parallelism
  end for

  it should s"map over a source with parallelism limit 10 (stress test)" in:
    for i <- 1 to 100 do
      info(s"iteration $i")

      // given
      val flow = Flow.fromIterable(1 to 10)

      def f(i: Int) =
        sleep(50.millis)
        i * 2

      // when
      val result = flow.mapParUnordered(10)(f).runToList()

      // then
      result should contain theSameElementsAs List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)

  it should "propagate errors" in supervised:
    // given
    val flow = Flow.fromIterable(1 to 10)
    val started = new AtomicInteger()

    // when
    val flow2 = flow.mapParUnordered(3) { i =>
      started.incrementAndGet()
      if i > 4 then throw new Exception("boom")
      i * 2
    }

    // then
    try
      flow2.runToList()
      fail("should have thrown")
    catch
      case e if e.getMessage == "boom" =>
        started.get() should be >= 2 // 1 needs to start & finish; 2 other need to start; and then the failing one has to start & proceed
        started.get() should be <= 7 // 4 successful + at most 3 taking up all the permits

  it should "complete running forks and not start new ones when the mapping function fails" in supervised:
    // given
    val trail = Trail()
    val flow = Flow.fromIterable(1 to 10)

    // when
    val flow2 = flow.mapParUnordered(2): i =>
      if i == 4 then
        sleep(100.millis)
        trail.add("exception")
        throw new Exception("boom")
      else
        sleep(200.millis)
        trail.add(s"done")
        i * 2

    val s2 = flow2.runToChannel()

    // then
    List(s2.receive(), s2.receive()) should contain only (2, 4)
    s2.receiveOrClosed() should matchPattern:
      case ChannelClosed.Error(reason) if reason.getMessage == "boom" =>
    s2.isClosedForReceive shouldBe true

    // checking if the forks aren't left running
    sleep(200.millis)

    // the fork that processes 4 would complete, thus adding "done" to the trail,
    // but it won't emit its result, since the channel would already be closed after the fork processing 3 failed
    trail.get shouldBe Vector("done", "done", "exception", "done")

  // TODO waiting for concat
  // it should "complete running forks and not start new ones when the upstream fails" in supervised {
  //   // given
  //   given StageCapacity = StageCapacity(0)
  //   val trail = Trail()
  //   val flow = Flow.fromValues(1, 2, 3).concat(Flow.failed(new RuntimeException("boom")))

  //   // when
  //   val s2 = flow.mapParUnordered(2) { i =>
  //     sleep(100.millis)
  //     trail.add(i.toString)
  //     i * 2
  //   }

  //   // then
  //   List(s2.receive(), s2.receive()) should contain only (2, 4)
  //   s2.receiveOrClosed() should matchPattern { case ChannelClosed.Error(reason) if reason.getMessage == "boom" => }
  //   s2.isClosedForReceive shouldBe true

  //   // checking if the forks aren't left running
  //   sleep(200.millis)

  //   trail.get should contain only ("1", "2", "3")
  // }

  it should "cancel running forks when the surrounding scope closes due to an error" in supervised:
    // given
    val trail = Trail()
    val flow = Flow.fromIterable(1 to 10)

    // when
    val flow2 = flow.mapParUnordered(2): i =>
      if i == 4 then
        sleep(100.millis)
        trail.add("exception")
        throw new Exception("boom")
      else
        sleep(200.millis)
        trail.add(s"done")
        i * 2

    val s2 = flow2.runToChannel()

    List(s2.receive(), s2.receive()) should contain only (2, 4)
    s2.receiveOrClosed() should matchPattern:
      case ChannelClosed.Error(reason) if reason.getMessage == "boom" =>
    s2.isClosedForReceive shouldBe true

    // then
    trail.get shouldBe Vector("done", "done", "exception")

  it should "emit downstream as soon as a value is ready, regardless of the incoming order" in supervised:
    // given
    val flow = Flow.fromIterable(1 to 5)
    val delays = Map(
      1 -> 100.millis,
      2 -> 10.millis,
      3 -> 50.millis,
      4 -> 500.millis,
      5 -> 200.millis
    )
    val expectedElements = delays.toList.sortBy(_._2).map(_._1)

    // when
    val flow2 = flow.mapParUnordered(5): i =>
      sleep(delays(i))
      i

    // then
    flow2.runToList() should contain theSameElementsInOrderAs expectedElements
end FlowOpsMapParUnorderedTest
