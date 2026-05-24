package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.Future

class FlowOpsFutureSourceTest extends AnyFlatSpec with Matchers:
  behavior of "futureSource"

  it should "return the original future failure when future fails" in:
    val failure = new RuntimeException("future failed")
    the[RuntimeException] thrownBy {
      Flow.fromFutureSource(Future.failed(failure)).runToList()
    } shouldBe failure

  it should "return future's source values" in supervised:
    Flow.fromFutureSource(Future.successful(Flow.fromValues(1, 2).runToChannel())).runToList() shouldBe List(1, 2)
end FlowOpsFutureSourceTest
