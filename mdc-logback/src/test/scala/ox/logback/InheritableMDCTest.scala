package ox.logback

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.MDC
import ox.fork

class InheritableMDCTest extends AnyFlatSpec with Matchers:
  InheritableMDC.init

  it should "make MDC values available in forks" in {
    InheritableMDC.supervisedWhere("a" -> "1", "b" -> "2") {
      MDC.put("c", "3") // should not be inherited

      fork {
        MDC.get("a") shouldBe "1"
        MDC.get("b") shouldBe "2"
        MDC.get("c") shouldBe null
      }.join()

      MDC.get("a") shouldBe "1"
      MDC.get("b") shouldBe "2"
      MDC.get("c") shouldBe "3"
    }
  }
end InheritableMDCTest
