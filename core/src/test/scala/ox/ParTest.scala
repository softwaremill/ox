package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.util.concurrent.atomic.AtomicInteger

class ParTest extends AnyFlatSpec with Matchers {
  "par" should "run computations in parallel" in {
    val trail = Trail()
    val result = par {
      Thread.sleep(200)
      trail.add("a")
      1
    } {
      Thread.sleep(100)
      trail.add("b")
      2
    }

    trail.add("done")

    result shouldBe (1, 2)
    trail.get shouldBe Vector("b", "a", "done")
  }

  it should "interrupt other computations in one fails" in {
    val trail = Trail()
    try
      par {
        Thread.sleep(200)
        trail.add("par 1 done")
      } {
        Thread.sleep(100)
        trail.add("exception")
        throw new Exception("boom")
      }
    catch case e: Exception if e.getMessage == "boom" => trail.add("catch")

    // checking if the forks aren't left running
    Thread.sleep(300)
    trail.add("all done")

    trail.get shouldBe Vector("exception", "catch", "all done")
  }

  "parLimit" should "run up to the given number of computations in parallel" in {
    val trail = Trail()
    val result = scoped {
      fork {
        forever {
          Thread.sleep(200)
          trail.add("y")
        }
      }

      Thread.sleep(100)
      parLimit(2)(
        (1 to 5).map(i =>
          () => {
            Thread.sleep(200)
            trail.add("x")
            i * 2
          }
        )
      )
    }

    result shouldBe List(2, 4, 6, 8, 10)
    trail.get shouldBe Vector("y", "x", "x", "y", "x", "x", "y", "x")
  }

  it should "interrupt other computations in one fails" in {
    val counter = new AtomicInteger(0)
    val trail = Trail()
    try
      parLimit(2)(
        (1 to 5).map(i =>
          () => {
            if counter.incrementAndGet() == 4 then
              Thread.sleep(10)
              trail.add("exception")
              throw new Exception("boom")
            else
              Thread.sleep(200)
              trail.add("x")
          }
        )
      )
    catch case e: Exception if e.getMessage == "boom" => trail.add("catch")

    Thread.sleep(300)
    trail.add("all done")

    trail.get shouldBe Vector("x", "x", "exception", "catch", "all done")
  }
}
