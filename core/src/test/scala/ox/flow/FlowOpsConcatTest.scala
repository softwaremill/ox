package ox.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FlowOpsConcatTest extends AnyFlatSpec with Matchers with Eventually:

  it should "concatenate flows" in:
    val s1 = Flow.fromValues("a", "b", "c")
    val s2 = Flow.fromValues("d", "e", "f")
    val s3 = Flow.fromValues("g", "h", "i")

    val s = Flow.concat(List(s1, s2, s3))

    s.runToList() shouldBe List("a", "b", "c", "d", "e", "f", "g", "h", "i")
end FlowOpsConcatTest
