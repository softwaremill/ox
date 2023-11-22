package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.util.concurrent.Semaphore

class ExceptionTest extends AnyFlatSpec with Matchers {
  class CustomException extends RuntimeException
  class CustomException2 extends RuntimeException
  class CustomException3(e: Exception) extends RuntimeException(e)

  "scoped" should "throw the exception thrown by a joined fork" in {
    val trail = Trail()
    try scoped(fork(throw CustomException()).join())
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.get shouldBe Vector("CustomException")
  }

  "supervised" should "throw the exception thrown in the scope" in {
    val trail = Trail()
    try supervised(throw CustomException())
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.get shouldBe Vector("CustomException")
  }

  it should "throw the exception thrown by a failing fork" in {
    val trail = Trail()
    try supervised(fork(throw CustomException()))
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.get shouldBe Vector("CustomException")
  }

  it should "interrupt other forks when there's a failure, add suppressed interrupted exceptions" in {
    val trail = Trail()
    val s = Semaphore(0)
    try
      supervised {
        fork {
          s.acquire() // will never complete
        }
        fork {
          s.acquire() // will never complete
        }
        fork {
          Thread.sleep(100)
          throw CustomException()
        }
      }
    catch
      case e: Exception =>
        val suppressed = e.getSuppressed.map(_.getClass.getSimpleName)
        trail.add(s"${e.getClass.getSimpleName}(suppressed=${suppressed.mkString(",")})")

    trail.get shouldBe Vector("CustomException(suppressed=InterruptedException,InterruptedException)")
  }

  it should "interrupt other forks when there's a failure, add suppressed custom exceptions" in {
    val trail = Trail()
    val s = Semaphore(0)
    try
      supervised {
        fork {
          try s.acquire() // will never complete
          finally throw CustomException2()
        }
        fork {
          Thread.sleep(100)
          throw CustomException()
        }
      }
    catch case e: Exception => addExceptionWithSuppressedTo(trail, e)

    trail.get shouldBe Vector("CustomException(suppressed=CustomException2)")
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

    trail.get shouldBe Vector("CustomException(suppressed=)")
  }

  it should "add an exception as suppressed, even if it wraps the original exception" in {
    val trail = Trail()
    try
      supervised {
        val f = fork {
          throw new CustomException()
        }
        try f.join() catch {
          case e: Exception => throw new CustomException3(e)
        }
      }
    catch case e: Exception => addExceptionWithSuppressedTo(trail, e)

    trail.get shouldBe Vector("CustomException(suppressed=CustomException3)")
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

  def addExceptionWithSuppressedTo(t: Trail, e: Throwable): Unit = {
    val suppressed = e.getSuppressed.map(_.getClass.getSimpleName)
    t.add(s"${e.getClass.getSimpleName}(suppressed=${suppressed.mkString(",")})")
  }
}
