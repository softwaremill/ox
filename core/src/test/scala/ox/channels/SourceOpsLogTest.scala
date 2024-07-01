package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.duration.DurationInt

class SourceOpsLogTest extends AnyFlatSpec with Matchers {

  it should "log elements passing through channel" in supervised {
    Source
      .fromValues(1, 2, 3, 4)
      .log("before")
      .map(_ * 2)
      .log("after")
      .toList shouldBe List(2, 4, 6, 8)
  }

  it should "logAsView elements passing through channel" in supervised {
    Source
      .fromValues(1, 2, 3, 4)
      .logAsView("before")
      .map(_ * 2)
      .logAsView("after")
      .toList shouldBe List(2, 4, 6, 8)
  }
}
