package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.syntax.foreachPar
import ox.util.{MaxCounter, Trail}

import scala.List
import scala.collection.IterableFactory
import scala.collection.immutable.Iterable

class ForeachParTest extends AnyFlatSpec with Matchers {
  "foreachPar" should "run computations in parallel" in {
    val InputElements = 17
    val TransformationMillis: Long = 100
    val trail = new Trail()

    val input = (0 to InputElements)
    def transformation(i: Int) = {
      Thread.sleep(TransformationMillis)
      trail.add(i.toString)
    }

    val start = System.currentTimeMillis()
    input.to(Iterable).foreachPar(5)(transformation)
    val end = System.currentTimeMillis()

    trail.get should contain theSameElementsAs input.map(_.toString)
    (end - start) should be < (InputElements * TransformationMillis)
  }

  it should "run not more computations than limit" in {
    val Parallelism = 5

    val input = (1 to 158)

    val maxCounter = MaxCounter()

    def transformation(i: Int) = {
      maxCounter.increment()
      Thread.sleep(10)
      maxCounter.decrement()
    }

    input.to(Iterable).foreachPar(Parallelism)(transformation)

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
      input.to(Iterable).foreachPar(5)(transformation)
    } catch {
      case e: Exception if e.getMessage == "boom" => trail.add("catch")
    }

    Thread.sleep(300)
    trail.add("all done")

    trail.get shouldBe Vector("exception", "catch", "all done")
  }
}
