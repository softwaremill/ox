package ox.channels.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicInteger
import ox.flow.Flow
import ox.channels.ChannelClosed
import ox.channels.ChannelClosedException

class FlowOpsMapParTest extends AnyFlatSpec with Matchers with Eventually:
  behavior of "mapPar"

  for parallelism <- 1 to 10 do
    it should s"map over a flow with parallelism limit $parallelism" in supervised:
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
        end try
      end f

      // update max running
      forkDiscard:
        var max = 0
        forever:
          max = math.max(max, running.get())
          maxRunning.set(max)
          sleep(10.millis)

      // when
      val result = flow.mapPar(parallelism)(f).runToList()

      // then
      result shouldBe List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
      maxRunning.get() shouldBe parallelism
  end for

  it should s"map over a flow with parallelism limit 10 (stress test)" in:
    for i <- 1 to 100 do
      info(s"iteration $i")

      // given
      val flow = Flow.fromIterable(1 to 10)

      def f(i: Int) =
        sleep(50.millis)
        i * 2

      // when
      val result = flow.mapPar(10)(f).runToList()

      // then
      result shouldBe List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)

  it should "propagate errors" in:
    // given
    val flow = Flow.fromIterable(1 to 10)
    val started = new AtomicInteger()

    // when
    val s2 = flow.mapPar(3): i =>
      started.incrementAndGet()
      if i > 4 then throw new Exception("boom")
      i * 2

    // then
    try
      s2.runToList().discard
      fail("should have thrown")
    catch
      case e if e.getCause().getMessage == "boom" =>
        started.get() should be >= 4
        started.get() should be <= 7 // 4 successful + at most 3 taking up all the permits

  it should "cancel other running forks when there's an error" in supervised:
    // given
    val trail = Trail()
    // Using exactly 2 elements with mapPar(2) so both always start concurrently — the semaphore has
    // 2 permits and there are only 2 forks, so both acquire immediately with no competition.
    // Element 2 fails after a short delay; element 1 should be cancelled during its long sleep.

    // when
    val s2 = Flow.fromIterable(List(1, 2))
      .mapPar(2): i =>
        if i == 2 then
          sleep(100.millis)
          trail.add("exception")
          throw new Exception("boom")
        else
          sleep(1000.millis)
          trail.add(s"done")
          i * 2
      .runToChannel()

    // then
    s2.receiveOrClosed() should matchPattern {
      case ChannelClosed.Error(ChannelClosedException.Error(reason)) if reason.getMessage == "boom" =>
    }

    // checking if the forks aren't left running; element 1 runs concurrently with the failing element 2
    // and should be cancelled before its 1s sleep completes (900ms cancellation window)
    sleep(200.millis)
    trail.get shouldBe Vector("exception") // element 1 cancelled (no "done"), element 2 added "exception"

  // Edge Cases
  it should "handle empty flow" in supervised:
    // given
    val flow = Flow.fromIterable(List.empty[Int])
    val processedCount = new AtomicInteger(0)

    // when
    val result = flow.mapPar(5): i =>
      processedCount.incrementAndGet()
      i * 2

    // then
    result.runToList() shouldBe List.empty
    processedCount.get() shouldBe 0

  it should "handle flow with exactly parallelism number of elements" in supervised:
    // given
    val parallelism = 3
    val flow = Flow.fromIterable(1 to parallelism)
    val running = new AtomicInteger(0)
    val maxRunning = new AtomicInteger(0)

    def f(i: Int) =
      val current = running.incrementAndGet()
      maxRunning.updateAndGet(current.max)
      try
        sleep(100.millis)
        i * 2
      finally running.decrementAndGet().discard
      end try
    end f

    // when
    val result = flow.mapPar(parallelism)(f).runToList()

    // then
    result shouldBe List(2, 4, 6)
    maxRunning.get() shouldBe parallelism

  it should "handle flow with less than parallelism number of elements" in supervised:
    // given
    val flow = Flow.fromIterable(1 to 2)
    val running = new AtomicInteger(0)
    val maxRunning = new AtomicInteger(0)

    def f(i: Int) =
      val current = running.incrementAndGet()
      maxRunning.updateAndGet(current.max)
      try
        sleep(100.millis)
        i * 2
      finally running.decrementAndGet().discard
      end try
    end f

    // when
    val result = flow.mapPar(5)(f).runToList()

    // then
    result shouldBe List(2, 4)
    maxRunning.get() shouldBe 2 // should never exceed actual number of elements

  // Order Preservation Tests
  it should "preserve order even with varying processing times" in supervised:
    // given
    val flow = Flow.fromIterable(1 to 10)

    def f(i: Int) =
      // Later elements finish faster to test order preservation
      val delay = if i <= 5 then (6 - i) * 50 else 50
      sleep(delay.millis)
      i * 2

    // when
    val result = flow.mapPar(3)(f).runToList()

    // then
    result shouldBe List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)

  it should "preserve order with random processing times" in supervised:
    // given
    val elements = 1 to 20
    val flow = Flow.fromIterable(elements)

    def f(i: Int) =
      // Random delay to test order preservation
      val delay = scala.util.Random.nextInt(100) + 10
      sleep(delay.millis)
      i

    // when
    val result = flow.mapPar(5)(f).runToList()

    // then
    result shouldBe elements.toList

  // Other
  it should "work with very high parallelism values" in supervised:
    // given
    val flow = Flow.fromIterable(1 to 5)
    val running = new AtomicInteger(0)
    val maxRunning = new AtomicInteger(0)

    def f(i: Int) =
      val current = running.incrementAndGet()
      maxRunning.updateAndGet(current.max)
      try
        sleep(50.millis)
        i * 2
      finally running.decrementAndGet().discard
      end try
    end f

    // when
    val result = flow.mapPar(1000)(f).runToList()

    // then
    result shouldBe List(2, 4, 6, 8, 10)
    maxRunning.get() shouldBe 5 // Should not exceed actual number of elements

end FlowOpsMapParTest
