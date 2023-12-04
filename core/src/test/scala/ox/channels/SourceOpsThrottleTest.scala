package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.duration.*
import ox.ElapsedTime

class SourceOpsThrottleTest extends AnyFlatSpec with Matchers with ElapsedTime {
  behavior of "Source.throttle"

  it should "not throttle the empty source" in supervised {
    val s = Source.empty[Int]
    val (result, executionTime) = measure { s.throttle(1, 1.second).toList }
    result shouldBe List.empty
    executionTime.toMillis should be < 1.second.toMillis
  }

  it should "throttle to specified elements per time units" in supervised {
    val s = Source.fromValues(1, 2)
    val (result, executionTime) = measure { s.throttle(1, 50.millis).toList }
    result shouldBe List(1, 2)
    executionTime.toMillis should (be >= 100L and be <= 150L)
  }

  it should "fail to throttle when elements <= 0" in supervised {
    val s = Source.empty[Int]
    the[IllegalArgumentException] thrownBy {
      s.throttle(-1, 50.millis)
    } should have message "requirement failed: elements must be > 0"
  }

  it should "fail to throttle when per lower than 1ms" in supervised {
    val s = Source.empty[Int]
    the[IllegalArgumentException] thrownBy {
      s.throttle(1, 50.nanos)
    } should have message "requirement failed: per time must be >= 1 ms"
  }
}
