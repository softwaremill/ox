package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsInterleaveAllTest extends AnyFlatSpec with Matchers {

  behavior of "Source.interleaveAll"

  it should "interleave no sources" in supervised {
    val s = Source.interleaveAll(List.empty)

    s.toList shouldBe empty
  }

  it should "interleave a single source" in supervised {
    val c = Source.fromValues(1, 2, 3)

    val s = Source.interleaveAll(List(c))

    s.toList shouldBe List(1, 2, 3)
  }

  it should "interleave multiple sources" in supervised {
    val c1 = Source.fromValues(1, 2, 3, 4, 5, 6, 7, 8)
    val c2 = Source.fromValues(10, 20, 30)
    val c3 = Source.fromValues(100, 200, 300, 400, 500)

    val s = Source.interleaveAll(List(c1, c2, c3))

    s.toList shouldBe List(1, 10, 100, 2, 20, 200, 3, 30, 300, 4, 400, 5, 500, 6, 7, 8)
  }

  it should "interleave multiple sources using custom segment size" in supervised {
    val c1 = Source.fromValues(1, 2, 3, 4, 5, 6, 7, 8)
    val c2 = Source.fromValues(10, 20, 30)
    val c3 = Source.fromValues(100, 200, 300, 400, 500)

    val s = Source.interleaveAll(List(c1, c2, c3), segmentSize = 2)

    s.toList shouldBe List(1, 2, 10, 20, 100, 200, 3, 4, 30, 300, 400, 5, 6, 500, 7, 8)
  }

  it should "interleave multiple sources using custom segment size and complete eagerly" in supervised {
    val c1 = Source.fromValues(1, 2, 3, 4, 5, 6, 7, 8)
    val c2 = Source.fromValues(10, 20, 30)
    val c3 = Source.fromValues(100, 200, 300, 400, 500)

    val s = Source.interleaveAll(List(c1, c2, c3), segmentSize = 2, eagerComplete = true)

    s.toList shouldBe List(1, 2, 10, 20, 100, 200, 3, 4, 30)
  }
}
