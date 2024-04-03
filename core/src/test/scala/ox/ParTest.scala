package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import scala.concurrent.duration.*

import java.util.concurrent.atomic.AtomicInteger

class ParTest extends AnyFlatSpec with Matchers {
  "par" should "run computations in parallel" in {
    val trail = Trail()
    val result = par(
      {
        sleep(200.millis)
        trail.add("a")
        1
      }, {
        sleep(100.millis)
        trail.add("b")
        2
      }
    )

    trail.add("done")

    result shouldBe (1, 2)
    trail.get shouldBe Vector("b", "a", "done")
  }

  it should "interrupt other computations in one fails" in {
    val trail = Trail()
    try
      par(
        {
          sleep(200.millis)
          trail.add("par 1 done")
        }, {
          sleep(100.millis)
          trail.add("exception")
          throw new Exception("boom")
        }
      )
    catch
      case e: Exception if e.getMessage == "boom" => trail.add("catch")

      // checking if the forks aren't left running
    sleep(300.millis)
    trail.add("all done")

    trail.get shouldBe Vector("exception", "catch", "all done")
  }

  "parLimit" should "run up to the given number of computations in parallel" in {
    val running = new AtomicInteger(0)
    val max = new AtomicInteger(0)
    val result = scoped {
      parLimit(2)(
        (1 to 9).map(i =>
          () => {
            val current = running.incrementAndGet()
            max.updateAndGet(m => if current > m then current else m)
            sleep(100.millis)
            running.decrementAndGet()
            i * 2
          }
        )
      )
    }

    result shouldBe List(2, 4, 6, 8, 10, 12, 14, 16, 18)
    max.get() shouldBe 2
  }

  it should "interrupt other computations in one fails" in {
    val counter = new AtomicInteger(0)
    val trail = Trail()
    try
      parLimit(2)(
        (1 to 5).map(i =>
          () => {
            if counter.incrementAndGet() == 4 then
              sleep(10.millis)
              trail.add("exception")
              throw new Exception("boom")
            else
              sleep(200.millis)
              trail.add("x")
          }
        )
      )
    catch case e: Exception if e.getMessage == "boom" => trail.add("catch")

    sleep(300.millis)
    trail.add("all done")

    trail.get shouldBe Vector("x", "x", "exception", "catch", "all done")
  }

  "parEither" should "run computations in parallel" in {
    val trail = Trail()
    val result = parEither(
      {
        sleep(200.millis)
        trail.add("a")
        Right(1)
      }, {
        sleep(100.millis)
        trail.add("b")
        Right(2)
      }
    )

    trail.add("done")

    result shouldBe Right((1, 2))
    trail.get shouldBe Vector("b", "a", "done")
  }

  it should "interrupt other computations in one fails" in {
    val trail = Trail()
    val result = parEither(
      {
        sleep(200.millis)
        trail.add("par 1 done")
        Right("ok")
      }, {
        sleep(100.millis)
        trail.add("exception")
        Left(-1)
      }
    )

    result shouldBe Left(-1)

    // checking if the forks aren't left running
    sleep(300.millis)
    trail.add("all done")

    trail.get shouldBe Vector("exception", "all done")
  }
}
