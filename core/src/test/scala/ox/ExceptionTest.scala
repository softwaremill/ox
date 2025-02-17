package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.util.concurrent.Semaphore

import scala.concurrent.duration.*

class ExceptionTest extends AnyFlatSpec with Matchers:
  class CustomException extends RuntimeException
  class CustomException2 extends RuntimeException
  class CustomException3(e: Exception) extends RuntimeException(e)

  "unsupervised" should "throw the exception thrown by a joined fork" in {
    val trail = Trail()
    try unsupervised(forkUnsupervised(throw CustomException()).join())
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.get shouldBe Vector("CustomException")
  }

  "supervised" should "throw the exception thrown in the scope" in {
    val trail = Trail()
    try supervised(throw CustomException())
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.get shouldBe Vector("CustomException")
  }

  it should "retain the original exception for context, as suppressed" in {
    val trail = Trail()
    try supervised(throw CustomException())
    catch case e: Exception => addExceptionWithSuppressedTo(trail, e)

    trail.get shouldBe Vector("CustomException(suppressed=ExecutionException)")
  }

  it should "throw the exception thrown by a failing fork" in {
    val trail = Trail()
    try supervised(forkUser(throw CustomException()))
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.get shouldBe Vector("CustomException")
  }

  it should "interrupt other forks when there's a failure, add suppressed interrupted exceptions" in {
    val trail = Trail()
    val s = Semaphore(0)
    try
      supervised {
        forkUser {
          s.acquire() // will never complete
        }.discard
        forkUser {
          s.acquire() // will never complete
        }.discard
        forkUser {
          sleep(100.millis)
          throw CustomException()
        }
      }
    catch case e: Exception => addExceptionWithSuppressedTo(trail, e)
    end try

    trail.get shouldBe Vector("CustomException(suppressed=ExecutionException,InterruptedException,InterruptedException)")
  }

  it should "interrupt other forks when there's a failure, add suppressed custom exceptions" in {
    val trail = Trail()
    val s = Semaphore(0)
    try
      supervised {
        forkUser {
          try s.acquire() // will never complete
          finally throw CustomException2()
        }.discard
        forkUser {
          sleep(100.millis)
          throw CustomException()
        }
      }
    catch case e: Exception => addExceptionWithSuppressedTo(trail, e)
    end try

    trail.get shouldBe Vector("CustomException(suppressed=ExecutionException,CustomException2)")
  }

  it should "not add the original exception as suppressed" in {
    val trail = Trail()
    try
      supervised {
        val f = fork {
          throw new CustomException()
        }
        f.join()
      }
    catch case e: Exception => addExceptionWithSuppressedTo(trail, e)

      // either join() might throw the original exception (shouldn't be suppressed), or it might be interrupted before
      // throwing (should be suppressed then)
    end try
    trail.get should (be(Vector("CustomException(suppressed=ExecutionException)")) or be(
      Vector("CustomException(suppressed=ExecutionException,InterruptedException)")
    ))
  }

  it should "add an exception as suppressed, even if it wraps the original exception" in {
    val trail = Trail()
    try
      supervised {
        val f = fork {
          throw new CustomException()
        }
        try f.join()
        catch case e: Exception => throw new CustomException3(e)
      }
    catch case e: Exception => addExceptionWithSuppressedTo(trail, e)
    end try

    trail.get shouldBe Vector("CustomException(suppressed=ExecutionException,CustomException3)")
  }

  "joinEither" should "catch the exception with which a fork ends" in {
    val r = supervised {
      val f = forkUnsupervised {
        throw CustomException()
      }
      f.joinEither()
    }

    r should matchPattern { case Left(e: CustomException) => }
  }

  def addExceptionWithSuppressedTo(t: Trail, e: Throwable): Unit =
    val suppressed = e.getSuppressed.map(_.getClass.getSimpleName)
    t.add(s"${e.getClass.getSimpleName}(suppressed=${suppressed.mkString(",")})")
end ExceptionTest
