package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsMapStatefulConcatTest extends AnyFlatSpec with Matchers {

  behavior of "Source.mapStatefulConcat"

  it should "deduplicate" in supervised {
    // given
    val c = Source.fromValues(1, 2, 2, 3, 2, 4, 3, 1, 5)

    // when
    val s = c.mapStatefulConcat(() => Set.empty[Int])((s, e) => (s + e, Option.unless(s.contains(e))(e)))

    // then
    s.toList shouldBe List(1, 2, 3, 4, 5)
  }

  it should "count consecutive" in supervised {
    // given
    val c = Source.fromValues("apple", "apple", "apple", "banana", "orange", "orange", "apple")

    // when
    val s = c.mapStatefulConcat(() => (Option.empty[String], 0))(
      { case ((previous, count), e) =>
        previous match
          case None      => ((Some(e), 1), None)
          case Some(`e`) => ((previous, count + 1), None)
          case Some(_)   => ((Some(e), 1), previous.map((_, count)))
      },
      { case (previous, count) => previous.map((_, count)) }
    )

    // then
    s.toList shouldBe List(
      ("apple", 3),
      ("banana", 1),
      ("orange", 2),
      ("apple", 1)
    )
  }

  it should "propagate errors in the mapping function" in supervised {
    // given
    given StageCapacity = StageCapacity(0) // so that the error isn't created too early
    val c = Source.fromValues("a", "b", "c")

    // when
    val s = c.mapStatefulConcat(() => 0) { (index, element) =>
      if (index < 2) (index + 1, Some(element))
      else throw new RuntimeException("boom")
    }

    // then
    s.receive() shouldBe "a"
    s.receive() shouldBe "b"
    s.receiveSafe() should matchPattern {
      case ChannelClosed.Error(reason) if reason.getMessage == "boom" =>
    }
  }

  it should "propagate errors in the completion callback" in supervised {
    // given
    given StageCapacity = StageCapacity(0)
    val c = Source.fromValues("a", "b", "c")

    // when
    val s = c.mapStatefulConcat(() => 0)((index, element) => (index + 1, Some(element)), _ => throw new RuntimeException("boom"))

    // then
    s.receive() shouldBe "a"
    s.receive() shouldBe "b"
    s.receive() shouldBe "c"
    s.receiveSafe() should matchPattern {
      case ChannelClosed.Error(reason) if reason.getMessage == "boom" =>
    }
  }
}
