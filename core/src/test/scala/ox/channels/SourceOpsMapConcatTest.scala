package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsMapConcatTest extends AnyFlatSpec with Matchers {

  behavior of "Source.mapConcat"

  it should "unfold iterables" in supervised {
    val c = Source.fromValues(List("a", "b", "c"), List("d", "e"), List("f"))
    val s = c.mapConcat(identity)
    s.toList shouldBe List("a", "b", "c", "d", "e", "f")
  }

  it should "transform elements" in supervised {
    val c = Source.fromValues("ab", "cd")
    val s = c.mapConcat { str => str.toList }

    s.toList shouldBe List('a', 'b', 'c', 'd')
  }

  it should "handle empty lists" in supervised {
    val c = Source.fromValues(List.empty, List("a"), List.empty, List("b", "c"))
    val s = c.mapConcat(identity)

    s.toList shouldBe List("a", "b", "c")
  }

  it should "propagate errors in the mapping function" in supervised {
    // given
    given StageCapacity = StageCapacity(0) // so that the error isn't created too early
    val c = Source.fromValues(List("a"), List("b", "c"), List("error here"))

    // when
    val s = c.mapConcat { element =>
      if (element != List("error here"))
        element
      else throw new RuntimeException("boom")
    }

    // then
    s.receive() shouldBe "a"
    s.receive() shouldBe "b"
    s.receive() shouldBe "c"
    s.receiveSafe() should matchPattern {
      case ChannelClosed.Error(reason) if reason.getMessage == "boom" =>
    }
  }
}
