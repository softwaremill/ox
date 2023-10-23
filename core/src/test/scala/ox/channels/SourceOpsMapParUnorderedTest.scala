package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import java.util.concurrent.atomic.AtomicInteger

class SourceOpsMapParUnorderedTest extends AnyFlatSpec with Matchers with Eventually {

  behavior of "Source.mapParUnordered"

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
      val result = s.mapParUnordered(parallelism)(f).toList

      // then
      result should contain theSameElementsAs List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
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
      val result = s.mapParUnordered(10)(f).toList

      // then
      result should contain theSameElementsAs List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
    }
  }

  it should "propagate errors" in scoped {
    // given
    val s = Source.fromIterable(1 to 10)
    val started = new AtomicInteger()

    // when
    val s2 = s.mapParUnordered(3) { i =>
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

  it should "complete running forks and not start new ones when the mapping function fails" in scoped {
    // given
    val trail = Trail()
    val s = Source.fromIterable(1 to 10)

    // when
    val s2 = s.mapParUnordered(2) { i =>
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
    List(s2.receive(), s2.receive()) should contain only (2, 4)
    s2.receive() should matchPattern { case ChannelClosed.Error(Some(reason)) if reason.getMessage == "boom" => }
    s2.isError shouldBe true

    // checking if the forks aren't left running
    Thread.sleep(200)

    // the fork that processes 4 would complete, thus adding "done" to the trail,
    // but it won't emit its result, since the channel would already be closed after the fork processing 3 failed
    trail.get shouldBe Vector("done", "done", "exception", "done")
  }

  it should "complete running forks and not start new ones when the upstream fails" in scoped {
    // given
    val trail = Trail()
    val s = Source.fromValues(1, 2, 3).concat(Source.failed(new RuntimeException("boom")))

    // when
    val s2 = s.mapParUnordered(2) { i =>
      Thread.sleep(100)
      trail.add(i.toString)
      i * 2
    }

    // then
    List(s2.receive(), s2.receive()) should contain only (2, 4)
    s2.receive() should matchPattern { case ChannelClosed.Error(Some(reason)) if reason.getMessage == "boom" => }
    s2.isError shouldBe true

    // checking if the forks aren't left running
    Thread.sleep(200)

    trail.get should contain only ("1", "2", "3")
  }

  it should "cancel running forks when the surrounding scope closes due to an error" in scoped {
    // given
    val trail = Trail()
    val s = Source.fromIterable(1 to 10)

    // when
    supervised {
      val s2 = s.mapParUnordered(2) { i =>
        if i == 4 then
          Thread.sleep(100)
          trail.add("exception")
          throw new Exception("boom")
        else
          Thread.sleep(200)
          trail.add(s"done")
          i * 2
      }

      List(s2.receive(), s2.receive()) should contain only (2, 4)
      s2.receive() should matchPattern { case ChannelClosed.Error(Some(reason)) if reason.getMessage == "boom" => }
      s2.isError shouldBe true
    }

    // then
    trail.get shouldBe Vector("done", "done", "exception")
  }

  it should "emit downstream as soon as a value is ready, regardless of the incoming order" in scoped {
    // given
    val s = Source.fromIterable(1 to 5)
    val delays = Map(
      1 -> 100,
      2 -> 10,
      3 -> 50,
      4 -> 500,
      5 -> 200
    )
    val expectedElements = delays.toList.sortBy(_._2).map(_._1)

    // when
    val s2 = s.mapParUnordered(5) { i =>
      Thread.sleep(delays(i))
      i
    }

    // then
    s2.toList should contain theSameElementsInOrderAs expectedElements
  }
}
