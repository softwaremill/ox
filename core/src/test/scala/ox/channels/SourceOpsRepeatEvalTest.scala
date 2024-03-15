package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.supervised

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

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

  it should "evaluate the element before each send, as long as it's defined" in supervised {
    var i = 0
    val evaluated = ConcurrentHashMap.newKeySet[Int]()
    val s = Source.repeatEvalWhileDefined {
      i += 1
      evaluated.add(i)
      if i < 5 then Some(i) else None
    }
    s.toList shouldBe List(1, 2, 3, 4)
    evaluated.asScala shouldBe Set(1, 2, 3, 4, 5)
  }
}
