package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.Future

class SourceOpsFutureSourceTest extends AnyFlatSpec with Matchers {
  import scala.concurrent.ExecutionContext.Implicits.global

  behavior of "SourceOps.futureSource"

  it should "return the original future failure when future fails" in supervised {
    val failure = new RuntimeException("future failed")
    Source.futureSource(Future.failed(failure)).receiveSafe() shouldBe ChannelClosed.Error(failure)
  }

  it should "return the original future failure when future fails with ExecutionException" in supervised {
    // according to https://docs.scala-lang.org/overviews/core/futures.html#exceptions
    // the InterruptedException is one of the exceptions wrapped in ExecutionException
    val failure = new InterruptedException("future interrupted")
    Source.futureSource(Future.failed(failure)).receiveSafe() shouldBe ChannelClosed.Error(failure)
  }

  it should "return future's source values" in supervised {
    Source.futureSource(Future.successful(Source.fromValues(1, 2))).toList shouldBe List(1, 2)
  }
}
