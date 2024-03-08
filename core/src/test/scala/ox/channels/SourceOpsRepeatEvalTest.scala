package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.supervised
import ox.channels.Source

class SourceOpsRepeatEvalTest extends AnyFlatSpec with Matchers {
  behavior of "SourceOps.repeatEval"

  it should "evaluate the element before each send" in supervised {
    var i = 0
    val s = Source.repeatEval {
      i += 1
      i
    }
    s.take(3).toList shouldBe List(1, 2, 3)
  }
}
