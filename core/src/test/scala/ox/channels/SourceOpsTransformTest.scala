package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsTransformTest extends AnyFlatSpec with Matchers {

  behavior of "Source.transform"

  it should "transform a source using a simple map" in {
    val c = Channel.buffered[Int](10)
    c.send(1)
    c.send(2)
    c.send(3)
    c.done()

    supervised {
      c.transform(_.map(_ * 2)).toList shouldBe List(2, 4, 6)
    }
  }

  it should "transform a source using a complex chain of operations" in {
    val c = Channel.buffered[Int](10)
    c.send(1)
    c.send(2)
    c.send(3)
    c.send(4)
    c.done()

    supervised {
      c.transform(_.drop(2).flatMap(i => List(i, i + 1, i + 2)).filter(_ % 2 == 0)).toList shouldBe List(4, 4, 6)
    }
  }

  it should "transform an infinite source" in {
    val c = Channel.rendezvous[Int]
    supervised {
      fork {
        var i = 0
        while true do
          c.send(i)
          i += 1
      }

      val s = c.transform(_.filter(_ % 2 == 0).flatMap(i => List(i, i + 1)))
      s.receive() shouldBe 0
      s.receive() shouldBe 1
      s.receive() shouldBe 2
    }
  }

  it should "transform an infinite source (stress test)" in {
    for (_ <- 1 to 1000) { // this nicely demonstrated two race conditions
      val c = Channel.rendezvous[Int]
      supervised {
        fork {
          var i = 0
          while true do
            c.send(i)
            i += 1
        }

        val s = c.transform(x => x)
        s.receive() shouldBe 0
      }
    }
  }
}
