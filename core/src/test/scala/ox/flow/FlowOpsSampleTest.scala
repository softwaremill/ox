package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsSampleTest extends AnyFlatSpec with Matchers:
  behavior of "sample"

  it should "not sample anything from an empty flow" in:
    val c = Flow.empty[Int]
    val s = c.sample(5)
    s.runToList() shouldBe List.empty

  it should "not sample anything when 'n == 0'" in:
    val c = Flow.fromValues(1 to 10: _*)
    val s = c.sample(0)
    s.runToList() shouldBe List.empty

  it should "sample every element of the flow when 'n == 1'" in:
    val c = Flow.fromValues(1 to 10: _*)
    val n = 1
    val s = c.sample(n)
    s.runToList() shouldBe (n to 10 by n)

  it should "sample every nth element of the flow" in:
    val c = Flow.fromValues(1 to 10: _*)
    val n = 3
    val s = c.sample(n)
    s.runToList() shouldBe (n to 10 by n)

end FlowOpsSampleTest
