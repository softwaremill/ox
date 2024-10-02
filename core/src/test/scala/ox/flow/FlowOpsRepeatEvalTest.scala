package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

class FlowOpsRepeatEvalTest extends AnyFlatSpec with Matchers:
  behavior of "repeatEval"

  it should "evaluate the element before each send" in:
    var i = 0
    val s = Flow.repeatEval {
      i += 1
      i
    }
    s.take(3).runToList() shouldBe List(1, 2, 3)

  it should "evaluate the element before each send, as long as it's defined" in:
    var i = 0
    val evaluated = ConcurrentHashMap.newKeySet[Int]()
    val s = Flow.repeatEvalWhileDefined {
      i += 1
      evaluated.add(i)
      if i < 5 then Some(i) else None
    }
    s.runToList() shouldBe List(1, 2, 3, 4)
    evaluated.asScala shouldBe Set(1, 2, 3, 4, 5)
end FlowOpsRepeatEvalTest
