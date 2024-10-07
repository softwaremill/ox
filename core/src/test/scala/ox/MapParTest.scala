package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.{MaxCounter, Trail}

import scala.collection.IterableFactory
import scala.collection.immutable.Iterable
import scala.concurrent.duration.*
import scala.List

class MapParTest extends AnyFlatSpec with Matchers:
  "mapPar" should "output the same type as input" in {
    val input = List(1, 2, 3)
    val result = input.mapPar(1)(identity)
    result shouldBe a[List[_]]
  }

  it should "run computations in parallel" in {
    val InputElements = 17
    val TransformationMillis = 100.millis

    val input = 0 to InputElements
    def transformation(i: Int) =
      sleep(TransformationMillis)
      i + 1

    val start = System.currentTimeMillis()
    val result = input.to(Iterable).mapPar(5)(transformation)
    val end = System.currentTimeMillis()

    result.toList should contain theSameElementsInOrderAs (input.map(_ + 1))
    (end - start) should be < (InputElements * TransformationMillis.toMillis)
  }

  it should "run not more computations than limit" in {
    val Parallelism = 5

    val input = (1 to 158)

    val maxCounter = new MaxCounter()

    def transformation(i: Int) =
      maxCounter.increment()
      sleep(10.millis)
      maxCounter.decrement()

    input.to(Iterable).mapPar(Parallelism)(transformation)

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

    try input.to(Iterable).mapPar(5)(transformation)
    catch case e: Exception if e.getMessage == "boom" => trail.add("catch")

    sleep(300.millis)
    trail.add("all done")

    trail.get shouldBe Vector("exception", "catch", "all done")
  }
end MapParTest
