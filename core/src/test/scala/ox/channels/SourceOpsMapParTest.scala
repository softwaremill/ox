package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

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
      result should be(List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20))
      maxRunning.get() shouldBe parallelism
    }
  }

  it should "propagate errors" in scoped {
    // given
    val s = Source.fromIterable(1 to 10)
    val started = new AtomicInteger()

    // when
    val s2 = s.mapPar(3) { i =>
      started.incrementAndGet()
      if i > 4 then
        throw new Exception("boom")
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
}
