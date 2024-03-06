package ox.retry

import org.scalatest.{EitherValues, TryValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.syntax.*

import scala.util.Failure

class RetrySyntaxTest extends AnyFlatSpec with Matchers with TryValues with EitherValues:

  behavior of "Retry syntax"

  it should "support operations that return a result directly" in {
    // given
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      if true then throw new RuntimeException(errorMessage)

    // when/then
    the[RuntimeException] thrownBy f.retry(RetryPolicy.immediate(3)) should have message errorMessage
    counter shouldBe 4
  }

  it should "support operations that return an Either" in {
    // given
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      Left(errorMessage)

    // when
    val result = f.retryEither(RetryPolicy.immediate(3))

    // then
    result.left.value shouldBe errorMessage
    counter shouldBe 4
  }
