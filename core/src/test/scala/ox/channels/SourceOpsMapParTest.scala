package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import java.util.concurrent.atomic.AtomicInteger

class SourceOpsMapParTest extends AnyFlatSpec with Matchers with Eventually {
  behavior of "mapPar"

  for (parallelism <- 1 to 10) {
    it should s"map over a source with parallelism limit $parallelism" in scoped {
      // given
      val s = Source.fromIterable(1 to 10)
      val running = new AtomicInteger(0)
      val maxRunning = new AtomicInteger(0)

      def f(i: Int) =
        running.incrementAndGet()
        try
          Thread.sleep(100)
          i * 2
        finally running.decrementAndGet()

      // update max running
      fork {
        var max = 0
        forever {
          max = math.max(max, running.get())
          maxRunning.set(max)
          Thread.sleep(10)
        }
      }

      // when
      val result = s.mapPar(parallelism)(f).toList

      // then
      result shouldBe List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
      maxRunning.get() shouldBe parallelism
    }
  }

  it should s"map over a source with parallelism limit 10 (stress test)" in scoped {
    for (i <- 1 to 100) {
      info(s"iteration $i")

      // given
      val s = Source.fromIterable(1 to 10)

      def f(i: Int) =
        Thread.sleep(50)
        i * 2

      // when
      val result = s.mapPar(10)(f).toList

      // then
      result shouldBe List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
    }
  }

  it should "propagate errors" in scoped {
    // given
    val s = Source.fromIterable(1 to 10)
    val started = new AtomicInteger()

    // when
    val s2 = s.mapPar(3) { i =>
      started.incrementAndGet()
      if i > 4 then throw new Exception("boom")
      i * 2
    }

    // then
    try
      s2.toList
      fail("should have thrown")
    catch
      case ChannelClosedException.Error(Some(reason)) if reason.getMessage == "boom" =>
        started.get() should be >= 4
        started.get() should be <= 7 // 4 successful + at most 3 taking up all the permits
  }

  it should "cancel other running forks when there's an error" in scoped {
    // given
    val trail = Trail()
    val s = Source.fromIterable(1 to 10)

    // when
    val s2 = s.mapPar(2) { i =>
      if i == 4 then
        Thread.sleep(100)
        trail.add("exception")
        throw new Exception("boom")
      else
        Thread.sleep(200)
        trail.add(s"done")
        i * 2
    }

    // then
    s2.receive() shouldBe 2
    s2.receive() shouldBe 4
    s2.receive() should matchPattern { case ChannelClosed.Error(Some(reason)) if reason.getMessage == "boom" => }

    // checking if the forks aren't left running
    Thread.sleep(200)
    trail.get shouldBe Vector("done", "done", "exception") // TODO: 3 isn't cancelled because it's already taken off the queue
  }
}
