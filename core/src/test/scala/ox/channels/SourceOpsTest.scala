package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.duration.DurationInt

class SourceOpsTest extends AnyFlatSpec with Matchers with Eventually {
  it should "timeout" in {
    supervised {
      val c = Source.timeout(100.millis)
      val start = System.currentTimeMillis()
      c.receive() shouldBe ()
      (System.currentTimeMillis() - start) shouldBe >=(100L)
      (System.currentTimeMillis() - start) shouldBe <=(150L)
    }
  }

  it should "zip two sources" in {
    supervised {
      val c1 = Source.fromValues(1, 2, 3, 0)
      val c2 = Source.fromValues(4, 5, 6)

      val s = c1.zip(c2)

      s.receive() shouldBe (1, 4)
      s.receive() shouldBe (2, 5)
      s.receive() shouldBe (3, 6)
      s.receive() shouldBe ChannelClosed.Done
    }
  }

  it should "merge two sources" in {
    supervised {
      val c1 = Source.fromValues(1, 2, 3)
      val c2 = Source.fromValues(4, 5, 6)

      val s = c1.merge(c2)

      s.toList.sorted shouldBe List(1, 2, 3, 4, 5, 6)
    }
  }

  it should "pipe one source to another" in {
    supervised {
      val c1 = Source.fromValues(1, 2, 3)
      val c2 = Channel.rendezvous[Int]

      fork { c1.pipeTo(c2) }

      c2.toList shouldBe List(1, 2, 3)
    }
  }

  it should "concatenate sources" in {
    supervised {
      val s1 = Source.fromValues("a", "b", "c")
      val s2 = Source.fromValues("d", "e", "f")
      val s3 = Source.fromValues("g", "h", "i")

      val s = Source.concat(List(() => s1, () => s2, () => s3))

      s.toList shouldBe List("a", "b", "c", "d", "e", "f", "g", "h", "i")
    }
  }
}
