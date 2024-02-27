package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.Future

class SourceOpsFutureTest extends AnyFlatSpec with Matchers {
  import scala.concurrent.ExecutionContext.Implicits.global

  behavior of "Source.future"

  it should "return the original future failure when future fails" in supervised {
    val failure = new RuntimeException("future failed")
    Source.future(Future.failed(failure)).receiveSafe() shouldBe ChannelClosed.Error(failure)
  }

  it should "return the original future failure when future fails with ExecutionException" in supervised {
    // according to https://docs.scala-lang.org/overviews/core/futures.html#exceptions
    // the InterruptedException is one of the exceptions wrapped in ExecutionException
    val failure = new InterruptedException("future interrupted")
    Source.future(Future.failed(failure)).receiveSafe() shouldBe ChannelClosed.Error(failure)
  }

  it should "return future value" in supervised {
    Source.future(Future.successful(1)).toList shouldBe List(1)
  }
}
