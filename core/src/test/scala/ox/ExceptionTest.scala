package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.util.concurrent.Semaphore

class ExceptionTest extends AnyFlatSpec with Matchers {
  class CustomException extends RuntimeException
  class CustomException2 extends RuntimeException

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
    catch
      case e: Exception =>
        val suppressed = e.getSuppressed.map(_.getClass.getSimpleName)
        trail.add(s"${e.getClass.getSimpleName}(suppressed=${suppressed.mkString(",")})")

    trail.get shouldBe Vector("CustomException(suppressed=CustomException2)")
  }
}
