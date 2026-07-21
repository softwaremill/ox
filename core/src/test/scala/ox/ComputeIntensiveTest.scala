package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

class ComputeIntensiveTest extends AnyFlatSpec with Matchers:
  "computeIntensive" should "return the result of the computation" in {
    computeIntensive(2 + 2) shouldBe 4
  }

  it should "run the computation on a platform thread from the default compute pool" in {
    val (name, isVirtual) = computeIntensive((Thread.currentThread().getName, Thread.currentThread().isVirtual))
    name should startWith("ox-compute-")
    isVirtual shouldBe false
  }

  it should "rethrow exceptions unchanged" in {
    val e = new RuntimeException("boom")
    val thrown = intercept[RuntimeException](computeIntensive(throw e))
    (thrown eq e) shouldBe true
  }

  it should "throw when setting a custom executor after the default one has been used" in {
    computeIntensive(1) shouldBe 1 // forces initialization of the default executor
    val executor = java.util.concurrent.Executors.newFixedThreadPool(1)
    try intercept[RuntimeException](setOxComputeExecutor(executor)).discard
    finally executor.shutdownNow().discard
  }
end ComputeIntensiveTest
