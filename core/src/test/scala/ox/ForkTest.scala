package ox

import jdk.incubator.concurrent.ScopedValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import ox.*
import ox.util.Trail

import java.time.Clock
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

class ForkTest extends AnyFlatSpec with Matchers {
  class CustomException extends RuntimeException

  "forkHold" should "run two forks concurrently" in {
    val trail = Trail()
    scoped {
      val f1 = forkHold {
        Thread.sleep(500)
        trail.add("f1 complete")
        5
      }
      val f2 = forkHold {
        Thread.sleep(1000)
        trail.add("f2 complete")
        6
      }
      trail.add("main mid")
      trail.add(s"result = ${f1.join() + f2.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "allow nested forks" in {
    val trail = Trail()
    scoped {
      val f1 = forkHold {
        val f2 = forkHold {
          Thread.sleep(1000)
          trail.add("f2 complete")
          6
        }

        Thread.sleep(500)
        trail.add("f1 complete")
        5 + f2.join()
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "allow extension method syntax" in {
    import ox.syntax.*
    val trail = Trail()
    scoped {
      val f1 = {
        val f2 = {
          Thread.sleep(1000)
          trail.add("f2 complete")
          6
        }.forkHold

        Thread.sleep(500)
        trail.add("f1 complete")
        5 + f2.join()
      }.forkHold

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "interrupt child forks when parents complete" in {
    val trail = Trail()
    scoped {
      val f1 = forkHold {
        forkHold {
          try
            Thread.sleep(1000)
            trail.add("f2 complete")
            6
          catch
            case e: InterruptedException =>
              trail.add("f2 interrupted")
              throw e
        }

        Thread.sleep(500)
        trail.add("f1 complete")
        5
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "result = 5", "f2 interrupted")
  }

  it should "throw the exception thrown by a joined fork" in {
    val trail = Trail()
    try scoped(forkHold(throw new CustomException()).join())
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.trail shouldBe Vector("CustomException")
  }

  "fork" should "propagate failures to the scope thread" in {
    val trail = Trail()
    try
      scoped {
        val f1 = fork {
          Thread.sleep(2000)
          trail.add("f1 done")
        }

        val f2 = fork {
          Thread.sleep(1000)
          throw new CustomException
        }

        f1.join()
        f2.join()
      }
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.trail shouldBe Vector("CustomException")
  }

  it should "not propagate interrupt exceptions" in {
    val trail = Trail()
    try
      scoped {
        fork {
          Thread.sleep(2000)
          trail.add("f1 done")
        }

        trail.add("main done")
      }
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    // child should be interrupted, but the error shouldn't propagate
    trail.trail shouldBe Vector("main done")
  }
}

@main def test1 =
  val log = LoggerFactory.getLogger("test1")
  val r = scoped {
    val f1 = forkHold {
      Thread.sleep(1000L)
      log.info("f1 done")
      5
    }
    val f2 = forkHold {
      Thread.sleep(2000L)
      log.info("f2 done")
      6
    }
    f1.join() + f2.join()
  }
  log.info("result: " + r)
