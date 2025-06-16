package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsSplitTest extends AnyFlatSpec with Matchers:
  behavior of "split"

  it should "split an empty flow" in:
    Flow.empty[Int].split(_ == 0).runToList() shouldBe List.empty

  it should "split a flow with no delimiters" in:
    Flow.fromValues(1, 2, 3).split(_ == 0).runToList() shouldBe List(Seq(1, 2, 3))

  it should "split a flow with delimiter at the beginning" in:
    Flow.fromValues(0, 1, 2, 3).split(_ == 0).runToList() shouldBe List(Seq(), Seq(1, 2, 3))

  it should "split a flow with delimiter at the end" in:
    Flow.fromValues(1, 2, 3, 0).split(_ == 0).runToList() shouldBe List(Seq(1, 2, 3))

  it should "split a flow with delimiter in the middle" in:
    Flow.fromValues(1, 2, 0, 3, 4).split(_ == 0).runToList() shouldBe List(Seq(1, 2), Seq(3, 4))

  it should "split a flow with multiple delimiters" in:
    Flow.fromValues(0, 1, 2, 3, 4, 5, 6, 7, 8, 9).split(_ % 4 == 0).runToList() shouldBe List(
      Seq(),
      Seq(1, 2, 3),
      Seq(5, 6, 7),
      Seq(9)
    )

  it should "split a flow with adjacent delimiters" in:
    Flow.fromValues(1, 0, 0, 2, 3).split(_ == 0).runToList() shouldBe List(Seq(1), Seq(), Seq(2, 3))

  it should "split a flow with only delimiters" in:
    Flow.fromValues(0, 0, 0).split(_ == 0).runToList() shouldBe List(Seq(), Seq(), Seq())

  it should "split a flow with single delimiter" in:
    Flow.fromValues(0).split(_ == 0).runToList() shouldBe List(Seq())

  it should "split a flow with single non-delimiter" in:
    Flow.fromValues(1).split(_ == 0).runToList() shouldBe List(Seq(1))

  it should "split a flow with multiple consecutive delimiters at the beginning" in:
    Flow.fromValues(0, 0, 1, 2).split(_ == 0).runToList() shouldBe List(Seq(), Seq(), Seq(1, 2))

  it should "split a flow with multiple consecutive delimiters at the end" in:
    Flow.fromValues(1, 2, 0, 0).split(_ == 0).runToList() shouldBe List(Seq(1, 2), Seq())

  it should "split a flow with string delimiters" in:
    Flow.fromValues("a", ",", "b", "c", ",", "d").split(_ == ",").runToList() shouldBe List(
      Seq("a"),
      Seq("b", "c"),
      Seq("d")
    )

  it should "split a flow using complex predicate" in:
    Flow.fromValues("apple", "BANANA", "cherry", "DATES", "elderberry").split(_.forall(_.isUpper)).runToList() shouldBe List(
      Seq("apple"),
      Seq("cherry"),
      Seq("elderberry")
    )

  it should "handle error propagation" in:
    val exception = new RuntimeException("test error")
    val flow = Flow.usingEmit[Int]: emit =>
      emit(1)
      emit(2)
      throw exception

    assertThrows[RuntimeException] {
      flow.split(_ == 0).runToList()
    }

  it should "split a large flow efficiently" in:
    val largeFlow = Flow.fromIterable(1 to 10000)
    val result = largeFlow.split(_ % 100 == 0).runToList()

    result.length shouldBe 100 // 100 chunks (delimiters at 100, 200, ..., 10000)
    result.head shouldBe (1 to 99).toSeq
    result(1) shouldBe (101 to 199).toSeq
    result.last shouldBe (9901 to 9999).toSeq

end FlowOpsSplitTest
