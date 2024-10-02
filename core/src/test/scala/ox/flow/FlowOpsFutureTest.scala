package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.Future

class FlowOpsFutureTest extends AnyFlatSpec with Matchers:
  behavior of "future"

  it should "return the original future failure when future fails" in:
    val failure = new RuntimeException("future failed")
    the[RuntimeException] thrownBy {
      Flow.fromFuture(Future.failed(failure)).runToList()
    } shouldBe failure

  it should "return future value" in:
    Flow.fromFuture(Future.successful(1)).runToList() shouldBe List(1)
end FlowOpsFutureTest
