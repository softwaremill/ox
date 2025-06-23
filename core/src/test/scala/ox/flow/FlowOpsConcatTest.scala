package ox.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.atomic.AtomicBoolean
import ox.discard

class FlowOpsConcatTest extends AnyFlatSpec with Matchers with Eventually:

  it should "concatenate flows" in:
    val s1 = Flow.fromValues("a", "b", "c")
    val s2 = Flow.fromValues("d", "e", "f")
    val s3 = Flow.fromValues("g", "h", "i")

    val s = Flow.concat(List(s1, s2, s3))

    s.runToList() shouldBe List("a", "b", "c", "d", "e", "f", "g", "h", "i")

  it should "concatenate flows using ++" in:
    val s1 = Flow.fromValues("a", "b", "c")
    val s2 = Flow.fromValues("d", "e", "f")
    val s3 = Flow.fromValues("g", "h", "i")

    val s = s1 ++ s2 ++ s3

    s.runToList() shouldBe List("a", "b", "c", "d", "e", "f", "g", "h", "i")

  it should "not evaluate subsequent flows if there's a failure" in:
    val evaluated = new AtomicBoolean(false)
    val f = Flow
      .failed(new IllegalStateException)
      .concat(Flow.usingEmit(emit =>
        evaluated.set(true)
        emit(1)
      ))

    intercept[IllegalStateException](f.runToList()).discard
    evaluated.get() shouldBe false
end FlowOpsConcatTest
