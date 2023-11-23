package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.syntax.mapParWith
import ox.util.Trail

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.IterableFactory
import scala.collection.immutable.Iterable
import scala.List

class MapParTest extends AnyFlatSpec with Matchers {
  "mapPar" should "output the same type as input" in {
    val input = List(1, 2, 3)
    val result = input.mapParWith(1)(identity)
    result shouldBe a[List[_]]
  }

  it should "run computations in parallel" in {
    val InputElements = 17
    val TransformationMillis: Long = 100

    val input = (0 to InputElements)
    def transformation(i: Int) = {
      Thread.sleep(TransformationMillis)
      i + 1
    }

    val start = System.currentTimeMillis()
    val result = input.to(Iterable).mapParWith(5)(transformation)
    val end = System.currentTimeMillis()

    result.toList should contain theSameElementsInOrderAs (input.map(_ + 1))
    (end - start) should be < (InputElements * TransformationMillis)
  }

  it should "run not more computations than limit" in {
    val Parallelism = 5

    val input = (1 to 158)

    class MaxCounter {
      val counter = new AtomicInteger(0)
      var max = 0
      def increment() = {
        counter.updateAndGet { c =>
          val inc = c + 1
          max = if (inc > max) inc else max
          inc
        }
      }
      def decrement() = {
        counter.decrementAndGet()
      }
    }

    val maxCounter = new MaxCounter

    def transformation(i: Int) = {
      maxCounter.increment()
      Thread.sleep(10)
      maxCounter.decrement()
    }

    input.to(Iterable).mapParWith(Parallelism)(transformation)

    maxCounter.max should be <= Parallelism
  }

  it should "interrupt other computations in one fails" in {
    val InputElements = 18
    val TransformationMillis: Long = 100
    val trail = Trail()

    val input = (0 to InputElements)

    def transformation(i: Int) = {
      if (i == 4) {
        trail.add("exception")
        throw new Exception("boom")
      } else {
        Thread.sleep(TransformationMillis)
        trail.add("transformation")
        i + 1
      }
    }

    try {
      input.to(Iterable).mapParWith(5)(transformation)
    } catch {
      case e: Exception if e.getMessage == "boom" => trail.add("catch")
    }

    Thread.sleep(300)
    trail.add("all done")

    trail.get shouldBe Vector("exception", "catch", "all done")
  }
}
