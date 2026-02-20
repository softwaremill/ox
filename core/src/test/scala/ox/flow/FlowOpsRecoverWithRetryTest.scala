package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosedException
import ox.resilience.RetryConfig
import ox.scheduling.Schedule

import java.util.concurrent.atomic.AtomicInteger

class FlowOpsRecoverWithRetryTest extends AnyFlatSpec with Matchers:

  behavior of "Flow.recoverWithRetry"

  it should "pass through elements when upstream flow succeeds" in:
    val flow = Flow.fromValues(1, 2, 3)
    val result = flow.recoverWithRetry(Schedule.immediate.maxRetries(3)) { case _: RuntimeException => Flow.fromValues(99) }.runToList()
    result shouldBe List(1, 2, 3)

  it should "switch to recovery flow on upstream failure" in:
    val flow = Flow.fromValues(1).concat(Flow.failed(new RuntimeException("boom")))
    val result = flow.recoverWithRetry(Schedule.immediate.maxRetries(0)) { case _: RuntimeException => Flow.fromValues(10, 20) }.runToList()
    result shouldBe List(1, 10, 20)

  it should "retry recovery flow on failure" in:
    val attemptCounter = new AtomicInteger(0)
    val maxRetries = 2

    val flow = Flow.fromValues(1).concat(Flow.failed(new RuntimeException("upstream")))
    val result = flow
      .recoverWithRetry(Schedule.immediate.maxRetries(maxRetries)) { case _: RuntimeException =>
        val attempt = attemptCounter.incrementAndGet()
        if attempt <= maxRetries then Flow.failed(new RuntimeException(s"recovery attempt $attempt failed"))
        else Flow.fromValues(42)
      }
      .runToList()

    result shouldBe List(1, 42)
    attemptCounter.get() shouldBe maxRetries + 1

  it should "propagate error after exhausting retries" in:
    val flow = Flow.fromValues(1).concat(Flow.failed(new RuntimeException("upstream")))
    val caught = the[ChannelClosedException.Error] thrownBy {
      flow
        .recoverWithRetry(Schedule.immediate.maxRetries(2)) { case _: RuntimeException =>
          Flow.failed(new RuntimeException("still failing"))
        }
        .runToList()
    }
    caught.getCause.getMessage shouldBe "still failing"

  it should "propagate unhandled exceptions without retrying" in:
    val flow = Flow.fromValues(1).concat(Flow.failed(new IllegalStateException("unhandled")))
    val caught = the[ChannelClosedException.Error] thrownBy {
      flow.recoverWithRetry(Schedule.immediate.maxRetries(3)) { case _: IllegalArgumentException => Flow.fromValues(99) }.runToList()
    }
    caught.getCause shouldBe an[IllegalStateException]

  it should "accept RetryConfig primary overload" in:
    val flow = Flow.fromValues(1).concat(Flow.failed(new RuntimeException("boom")))
    val config = RetryConfig[Throwable, Unit](Schedule.immediate.maxRetries(0))
    val result = flow.recoverWithRetry(config) { case _: RuntimeException => Flow.fromValues(42) }.runToList()
    result shouldBe List(1, 42)

end FlowOpsRecoverWithRetryTest
