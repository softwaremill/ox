package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsCollectTest extends AnyFlatSpec with Matchers:
  behavior of "Source.collect"

  it should "collect over a source" in {
    supervised {
      val c = Source.fromValues(1 to 10: _*)

      val s = c.collect {
        case i if i % 2 == 0 => i * 10
      }

      s.toList shouldBe (2 to 10 by 2).map(_ * 10)
    }
  }
