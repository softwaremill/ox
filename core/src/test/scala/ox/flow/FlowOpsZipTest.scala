package ox.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FlowOpsZipTest extends AnyFlatSpec with Matchers with Eventually:

  it should "zip two sources" in:
    val c1 = Flow.fromValues(1, 2, 3, 0)
    val c2 = Flow.fromValues(4, 5, 6)

    val s = c1.zip(c2).runToList()

    s shouldBe List((1, 4), (2, 5), (3, 6))
end FlowOpsZipTest
