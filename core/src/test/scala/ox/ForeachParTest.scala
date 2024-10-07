package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.{MaxCounter, Trail}

import scala.List
import scala.collection.IterableFactory
import scala.collection.immutable.Iterable
import scala.concurrent.duration.*

class ForeachParTest extends AnyFlatSpec with Matchers:
  "foreachPar" should "run computations in parallel" in {
    val InputElements = 17
    val TransformationMillis = 100.millis
    val trail = new Trail()

    val input = 0 to InputElements
    def transformation(i: Int): Unit =
      sleep(TransformationMillis)
      trail.add(i.toString)

    val start = System.currentTimeMillis()
    input.to(Iterable).foreachPar(5)(transformation)
    val end = System.currentTimeMillis()

    trail.get should contain theSameElementsAs input.map(_.toString)
    (end - start) should be < (InputElements * TransformationMillis.toMillis)
  }

  it should "run not more computations than limit" in {
    val Parallelism = 5

    val input = 1 to 158

    val maxCounter = MaxCounter()

    def transformation(i: Int) =
      maxCounter.increment()
      sleep(10.millis)
      maxCounter.decrement()

    input.to(Iterable).foreachPar(Parallelism)(transformation)

    maxCounter.max should be <= Parallelism
  }

  it should "interrupt other computations in one fails" in {
    val InputElements = 18
    val TransformationMillis = 100.millis
    val trail = Trail()

    val input = 0 to InputElements

    def transformation(i: Int) =
      if i == 4 then
        trail.add("exception")
        throw new Exception("boom")
      else {
        sleep(TransformationMillis)
        trail.add("transformation")
        i + 1
      }

    try input.to(Iterable).foreachPar(5)(transformation)
    catch case e: Exception if e.getMessage == "boom" => trail.add("catch")

    sleep(300.millis)
    trail.add("all done")

    trail.get shouldBe Vector("exception", "catch", "all done")
  }
end ForeachParTest
