package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsSplitOnTest extends AnyFlatSpec with Matchers:
  behavior of "splitOn"

  it should "split an empty flow" in:
    Flow.empty[Int].splitOn(List(0)).runToList() shouldBe List.empty

  it should "split a flow with no delimiters" in:
    Flow.fromValues(1, 2, 3).splitOn(List(0)).runToList() shouldBe List(Seq(1, 2, 3))

  it should "split a flow with single-element delimiter at the beginning" in:
    Flow.fromValues(0, 1, 2, 3).splitOn(List(0)).runToList() shouldBe List(Seq(), Seq(1, 2, 3))

  it should "split a flow with single-element delimiter at the end" in:
    Flow.fromValues(1, 2, 3, 0).splitOn(List(0)).runToList() shouldBe List(Seq(1, 2, 3))

  it should "split a flow with single-element delimiter in the middle" in:
    Flow.fromValues(1, 2, 0, 3, 4).splitOn(List(0)).runToList() shouldBe List(Seq(1, 2), Seq(3, 4))

  it should "split a flow with multiple single-element delimiters" in:
    Flow.fromValues(1, 0, 2, 3, 0, 4, 5).splitOn(List(0)).runToList() shouldBe List(
      Seq(1),
      Seq(2, 3),
      Seq(4, 5)
    )

  it should "split a flow with adjacent single-element delimiters" in:
    Flow.fromValues(1, 0, 0, 2, 3).splitOn(List(0)).runToList() shouldBe List(Seq(1), Seq(), Seq(2, 3))

  it should "split a flow with only single-element delimiters" in:
    Flow.fromValues(0, 0, 0).splitOn(List(0)).runToList() shouldBe List(Seq(), Seq(), Seq())

  it should "split a flow with multi-element delimiter at the beginning" in:
    Flow.fromValues(0, 1, 2, 3, 4).splitOn(List(0, 1)).runToList() shouldBe List(Seq(), Seq(2, 3, 4))

  it should "split a flow with multi-element delimiter at the end" in:
    Flow.fromValues(1, 2, 3, 0, 1).splitOn(List(0, 1)).runToList() shouldBe List(Seq(1, 2, 3))

  it should "split a flow with multi-element delimiter in the middle" in:
    Flow.fromValues(1, 2, 0, 1, 3, 4).splitOn(List(0, 1)).runToList() shouldBe List(Seq(1, 2), Seq(3, 4))

  it should "split a flow with multiple multi-element delimiters" in:
    Flow.fromValues(1, 2, 0, 1, 3, 4, 0, 1, 5, 6).splitOn(List(0, 1)).runToList() shouldBe List(
      Seq(1, 2),
      Seq(3, 4),
      Seq(5, 6)
    )

  it should "split a flow with adjacent multi-element delimiters" in:
    Flow.fromValues(1, 2, 0, 1, 0, 1, 3, 4).splitOn(List(0, 1)).runToList() shouldBe List(Seq(1, 2), Seq(), Seq(3, 4))

  it should "split a flow with only multi-element delimiters" in:
    Flow.fromValues(0, 1, 0, 1).splitOn(List(0, 1)).runToList() shouldBe List(Seq(), Seq())

  it should "split a flow with overlapping patterns" in:
    // Pattern: [1, 2, 1] in input [1, 2, 1, 2, 1]
    // Should find delimiter at positions 0-2, leaving [2, 1] as remainder
    Flow.fromValues(1, 2, 1, 2, 1).splitOn(List(1, 2, 1)).runToList() shouldBe List(Seq(), Seq(2, 1))

  it should "split a flow with complex overlapping patterns" in:
    // Pattern: [1, 1, 2] in input [1, 1, 1, 2, 3, 1, 1, 2, 4]
    // Should find delimiters properly
    Flow.fromValues(1, 1, 1, 2, 3, 1, 1, 2, 4).splitOn(List(1, 1, 2)).runToList() shouldBe List(Seq(1), Seq(3), Seq(4))

  it should "handle empty delimiter by returning entire input as single chunk" in:
    Flow.fromValues(1, 2, 3, 4, 5).splitOn(List.empty).runToList() shouldBe List(Seq(1, 2, 3, 4, 5))

  it should "handle empty delimiter with empty input" in:
    Flow.empty[Int].splitOn(List.empty).runToList() shouldBe List.empty

  it should "split a flow with string elements" in:
    Flow.fromValues("a", "b", ",", "c", "d", ",", "e").splitOn(List(",")).runToList() shouldBe List(
      Seq("a", "b"),
      Seq("c", "d"),
      Seq("e")
    )

  it should "split a flow with multi-element string delimiter" in:
    Flow
      .fromValues("hello", "world", "END", "OF", "LINE", "foo", "bar", "END", "OF", "LINE", "baz")
      .splitOn(List("END", "OF", "LINE"))
      .runToList() shouldBe List(
      Seq("hello", "world"),
      Seq("foo", "bar"),
      Seq("baz")
    )

  it should "handle delimiter longer than input" in:
    Flow.fromValues(1, 2).splitOn(List(1, 2, 3, 4)).runToList() shouldBe List(Seq(1, 2))

  it should "handle single element matching start of multi-element delimiter" in:
    Flow.fromValues(1, 2, 3).splitOn(List(1, 5)).runToList() shouldBe List(Seq(1, 2, 3))

  it should "handle partial delimiter match at end" in:
    Flow.fromValues(1, 2, 3, 4, 5).splitOn(List(4, 5, 6)).runToList() shouldBe List(Seq(1, 2, 3, 4, 5))

  it should "split with delimiter that appears multiple times in sequence" in:
    Flow.fromValues(1, 2, 2, 2, 3, 4, 2, 2, 5).splitOn(List(2, 2)).runToList() shouldBe List(Seq(1), Seq(2, 3, 4), Seq(5))

  it should "handle error propagation" in:
    val exception = new RuntimeException("test error")
    val flow = Flow.usingEmit[Int]: emit =>
      emit(1)
      emit(2)
      throw exception

    assertThrows[RuntimeException] {
      flow.splitOn(List(0)).runToList()
    }

  it should "split a large flow efficiently" in:
    val delimiter = List(100, 200)
    val largeInput = (1 to 50) ++ delimiter ++ (51 to 100) ++ delimiter ++ (101 to 150)
    val result = Flow.fromIterable(largeInput).splitOn(delimiter).runToList()

    result shouldBe List(
      (1 to 50).toSeq,
      (51 to 100).toSeq,
      (101 to 150).toSeq
    )

  it should "handle repeated delimiter pattern correctly" in:
    // Input: [A, B, A, B, A, B, C] with delimiter [A, B]
    val input = List("A", "B", "A", "B", "A", "B", "C")
    val delimiter = List("A", "B")
    val result = Flow.fromIterable(input).splitOn(delimiter).runToList()

    result shouldBe List(Seq(), Seq(), Seq(), Seq("C"))

  // Test given-when-then structure as requested by user rules
  it should "properly split when given a flow with delimiter patterns" in:
    // given
    val input = Flow.fromValues(1, 2, 9, 9, 3, 4, 9, 9, 5, 6, 7)
    val delimiter = List(9, 9)

    // when
    val result = input.splitOn(delimiter).runToList()

    // then - successful scenario
    result shouldBe List(Seq(1, 2), Seq(3, 4), Seq(5, 6, 7))

  it should "handle erroneous scenarios when delimiter processing fails" in:
    // given
    val input = Flow.fromValues(1, 2, 3).concat(Flow.failed(new IllegalStateException("split error")))
    val delimiter = List(2)

    // when & then - erroneous scenario
    assertThrows[IllegalStateException] {
      input.splitOn(delimiter).runToList()
    }

end FlowOpsSplitOnTest
