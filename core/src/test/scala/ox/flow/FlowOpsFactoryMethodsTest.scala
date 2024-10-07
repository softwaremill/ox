package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsFactoryMethodsTest extends AnyFlatSpec with Matchers:

  behavior of "factory methods"

  it should "create a flow from a fork" in supervised:
    val f = fork(1)
    val c = Flow.fromFork(f)
    c.runToList() shouldBe List(1)

  it should "create an iterating flow" in:
    val c = Flow.iterate(1)(_ + 1)
    c.take(3).runToList() shouldBe List(1, 2, 3)

  it should "unfold a function" in:
    val c = Flow.unfold(0)(i => if i < 3 then Some((i, i + 1)) else None)
    c.runToList() shouldBe List(0, 1, 2)

  it should "produce a range" in:
    Flow.range(1, 5, 1).runToList() shouldBe List(1, 2, 3, 4, 5)
    Flow.range(1, 5, 2).runToList() shouldBe List(1, 3, 5)
    Flow.range(1, 11, 3).runToList() shouldBe List(1, 4, 7, 10)
end FlowOpsFactoryMethodsTest
