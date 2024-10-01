package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsTakeWhileTest extends AnyFlatSpec with Matchers:
  behavior of "takeWhile"

  it should "not take from the empty flow" in:
    Flow.empty[Int].takeWhile(_ < 3).runToList() shouldBe List.empty

  it should "take as long as predicate is satisfied" in:
    Flow.fromValues(1, 2, 3).takeWhile(_ < 3).runToList() shouldBe List(1, 2)

  it should "take the failed element if includeFirstFailing = true" in:
    Flow.fromValues(1, 2, 3, 4).takeWhile(_ < 3, includeFirstFailing = true).runToList() shouldBe List(1, 2, 3)

  it should "work if all elements match the predicate" in:
    Flow.fromValues(1, 2, 3).takeWhile(_ < 5).runToList() shouldBe List(1, 2, 3)

  it should "fail the sourcewith the same exception as the initial source" in:
    val f = Flow.fromSender: send =>
      send(1)
      throw new IllegalArgumentException()

    an[IllegalArgumentException] should be thrownBy (f.runToList())

  it should "not take if predicate fails for first or more elements" in:
    Flow.fromValues(3, 2, 1).takeWhile(_ < 3).runToList() shouldBe Nil
end FlowOpsTakeWhileTest
