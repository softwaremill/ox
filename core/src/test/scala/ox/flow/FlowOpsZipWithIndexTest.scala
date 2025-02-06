package ox.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FlowOpsZipWithIndexTest extends AnyFlatSpec with Matchers with Eventually:
  behavior of "zipWithIndex"

  it should "not zip anything from an empty flow" in:
    val c = Flow.empty[Int]
    val s = c.zipWithIndex
    s.runToList() shouldBe List.empty

  it should "zip flow with index" in:
    val c = Flow.fromValues(1 to 5: _*)
    val s = c.zipWithIndex
    s.runToList() shouldBe List((1, 0), (2, 1), (3, 2), (4, 3), (5, 4))

end FlowOpsZipWithIndexTest
