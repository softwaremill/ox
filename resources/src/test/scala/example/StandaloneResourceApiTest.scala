package example

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.use

class StandaloneResourceApiTest extends AnyFlatSpec with Matchers:
  "the standalone API" should "inline into code outside the ox package" in {
    var released = false

    val result = use(21, _ => released = true)(_ * 2)

    result shouldBe 42
    released shouldBe true
  }
end StandaloneResourceApiTest
