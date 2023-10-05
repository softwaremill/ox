package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsInterleaveTest extends AnyFlatSpec with Matchers {

  behavior of "Source.interleave"

  it should "interleave with an empty source" in scoped {
    val c1 = Source.fromValues(1, 2, 3)
    val c2 = Source.fromValues()

    val s1 = c1.interleave(c2)

    s1.toList shouldBe List(1, 2, 3)
  }

  it should "interleave two sources with default segment size" in scoped {
    val c1 = Source.fromValues(1, 3, 5)
    val c2 = Source.fromValues(2, 4, 6)

    val s = c1.interleave(c2)

    s.toList shouldBe List(1, 2, 3, 4, 5, 6)
  }

  it should "interleave two sources with default segment size and different lengths" in scoped {
    val c1 = Source.fromValues(1, 3, 5)
    val c2 = Source.fromValues(2, 4, 6, 8, 10, 12)

    val s = c1.interleave(c2)

    s.toList shouldBe List(1, 2, 3, 4, 5, 6, 8, 10, 12)
  }

  it should "interleave two sources with custom segment size" in scoped {
    val c1 = Source.fromValues(1, 2, 3, 4)
    val c2 = Source.fromValues(10, 20, 30, 40)

    val s = c1.interleave(c2, segmentSize = 2)

    s.toList shouldBe List(1, 2, 10, 20, 3, 4, 30, 40)
  }

  it should "interleave two sources with custom segment size and different lengths" in scoped {
    val c1 = Source.fromValues(1, 2, 3, 4, 5, 6, 7)
    val c2 = Source.fromValues(10, 20, 30, 40)

    val s = c1.interleave(c2, segmentSize = 2)

    s.toList shouldBe List(1, 2, 10, 20, 3, 4, 30, 40, 5, 6, 7)
  }
}
