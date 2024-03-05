package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Semaphore

class AppErrorTest extends AnyFlatSpec with Matchers:
  "supervisedError" should "return the app error from the main body" in {
    supervisedError(EitherMode[Int])(Left(10)) shouldBe Left(10)
  }

  it should "return success from the main body" in {
    supervisedError(EitherMode[Int])(Right("ok")) shouldBe Right("ok")
  }

  it should "return the app error returned by a failing fork" in {
    supervisedError(EitherMode[Int]) { forkUserError(Left(10)); Right(()) } shouldBe Left(10)
  }

  it should "return success from the main body if a fork is successful" in {
    supervisedError(EitherMode[Int]) { forkUserError(Right("ok")); Right(()) } shouldBe Right(())
  }

  it should "interrupt other forks if one fails" in {
    val s = Semaphore(0)
    supervisedError(EitherMode[Int]) {
      forkUser {
        s.acquire() // will never complete
      }
      forkUser {
        s.acquire() // will never complete
      }
      forkUserError {
        Thread.sleep(100)
        Left(-1)
      }
      Right(())
    } shouldBe Left(-1)
  }
