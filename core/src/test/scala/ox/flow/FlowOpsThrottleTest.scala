package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.duration.*
import ox.util.ElapsedTime

class FlowOpsThrottleTest extends AnyFlatSpec with Matchers with ElapsedTime:
  behavior of "throttle"

  it should "not throttle the empty source" in:
    val s = Flow.empty[Int]
    val (result, executionTime) = measure:
      s.throttle(1, 1.second).runToList()
    result shouldBe List.empty
    executionTime.toMillis should be < 1.second.toMillis

  it should "throttle to specified elements per time units" in:
    val s = Flow.fromValues(1, 2)
    val (result, executionTime) = measure:
      s.throttle(1, 50.millis).runToList()
    result shouldBe List(1, 2)
    executionTime.toMillis should (be >= 100L and be <= 150L)

  it should "fail to throttle when elements <= 0" in:
    val s = Flow.empty[Int]
    the[IllegalArgumentException] thrownBy {
      s.throttle(-1, 50.millis)
    } should have message "requirement failed: elements must be > 0"

  it should "fail to throttle when per lower than 1ms" in:
    val s = Flow.empty[Int]
    the[IllegalArgumentException] thrownBy {
      s.throttle(1, 50.nanos)
    } should have message "requirement failed: per time must be >= 1 ms"
end FlowOpsThrottleTest
