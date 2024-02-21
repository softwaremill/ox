package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsMapTest extends AnyFlatSpec with Matchers {

  behavior of "Source.map"

  it should "map over a source" in {
    supervised {
      val c = Channel[Int]()
      fork {
        c.send(1)
        c.send(2)
        c.send(3)
        c.done()
      }

      val s = c.map(_ * 10)

      s.receive() shouldBe 10
      s.receive() shouldBe 20
      s.receive() shouldBe 30
      s.receive() shouldBe ChannelClosed.Done
    }
  }

  it should "map over a source (stress test)" in {
    // this demonstrated a race condition where a cell was added by select to the waiting list by T1, completed by T2,
    // which then subsequently completed the stream; only then T1 wakes up, and checks if no new elements have been added
    for (_ <- 1 to 100000) {
      supervised {
        val c = Channel[Int]()
        fork {
          c.send(1)
          c.done()
        }

        val s = c.map(_ * 10)

        s.receive() shouldBe 10
        s.receive() shouldBe ChannelClosed.Done
      }
    }
  }

  it should "map over a source using for-syntax" in {
    supervised {
      val c = Channel[Int]()
      fork {
        c.send(1)
        c.send(2)
        c.send(3)
        c.done()
      }

      val s = for {
        v <- c
      } yield v * 2

      s.receive() shouldBe 2
      s.receive() shouldBe 4
      s.receive() shouldBe 6
      s.receive() shouldBe ChannelClosed.Done
    }
  }
}
