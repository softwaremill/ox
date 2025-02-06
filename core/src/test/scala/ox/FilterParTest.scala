package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.{MaxCounter, Trail}

import scala.List
import scala.collection.IterableFactory
import scala.collection.immutable.Iterable
import scala.concurrent.duration.*
import scala.annotation.nowarn

class FilterParTest extends AnyFlatSpec with Matchers:
  "filterPar" should "output the same type as input" in {
    val input = List(1, 2, 3)
    val result = input.filterPar(1)(_ => true)
    result shouldBe a[List[_]]
  }

  it should "run computations in parallel" in {
    val InputElements = 17
    val TransformationMillis = 100.millis

    val input = 0 to InputElements
    def predicate(i: Int) =
      sleep(TransformationMillis)
      i % 2 == 0

    val start = System.currentTimeMillis()
    val result = input.to(Iterable).filterPar(5)(predicate)
    val end = System.currentTimeMillis()

    result.toList should contain theSameElementsInOrderAs List(0, 2, 4, 6, 8, 10, 12, 14, 16)
    (end - start) should be < (InputElements * TransformationMillis.toMillis)
  }

  it should "run not more computations than limit" in {
    val Parallelism = 5

    val input = 1 to 158

    val maxCounter = new MaxCounter()

    def predicate(@nowarn i: Int) =
      maxCounter.increment().discard
      sleep(10.millis)
      maxCounter.decrement().discard
      true

    input.to(Iterable).filterPar(Parallelism)(predicate).discard

    maxCounter.max should be <= Parallelism
  }

  it should "interrupt other computations in one fails" in {
    val InputElements = 18
    val TransformationMillis = 100.millis
    val trail = Trail()

    val input = (0 to InputElements)

    def predicate(i: Int) =
      if i == 4 then
        trail.add("exception")
        throw new Exception("boom")
      else
        sleep(TransformationMillis)
        trail.add("transformation")
        true

    try input.to(Iterable).filterPar(5)(predicate)
    catch case e: Exception if e.getMessage == "boom" => trail.add("catch")

    sleep(300.millis)
    trail.add("all done")

    trail.get shouldBe Vector("exception", "catch", "all done")
  }
end FilterParTest
