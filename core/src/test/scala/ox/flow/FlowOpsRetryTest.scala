package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosedException
import ox.resilience.ResultPolicy
import ox.resilience.RetryConfig
import ox.scheduling.Schedule
import ox.util.ElapsedTime

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class FlowOpsRetryTest extends AnyFlatSpec with Matchers with ElapsedTime:

  behavior of "Flow.retry"

  it should "successfully run a flow without retries when no errors occur" in:
    // given
    val flow = Flow.fromValues(1, 2, 3)

    // when
    val result = flow.retry(Schedule.immediate.maxRetries(3)).runToList()

    // then
    result shouldBe List(1, 2, 3)

  it should "retry a failing flow with immediate schedule" in:
    // given
    val attemptCounter = new AtomicInteger(0)
    val maxRetries = 3

    val flow = Flow.usingEmit[Int] { emit =>
      val attempt = attemptCounter.incrementAndGet()
      if attempt <= maxRetries then throw new RuntimeException(s"attempt $attempt failed")
      else emit(42)
    }

    // when
    val result = flow.retry(Schedule.immediate.maxRetries(maxRetries)).runToList()

    // then
    result shouldBe List(42)
    attemptCounter.get() shouldBe maxRetries + 1

  it should "retry a failing flow with fixed interval schedule" in:
    // given
    val attemptCounter = new AtomicInteger(0)
    val maxRetries = 2
    val interval = 50.millis

    val flow = Flow.usingEmit[Int] { emit =>
      val attempt = attemptCounter.incrementAndGet()
      if attempt <= maxRetries then throw new RuntimeException(s"attempt $attempt failed")
      else emit(100)
    }

    // when
    val (result, elapsedTime) = measure {
      flow.retry(Schedule.fixedInterval(interval).maxRetries(maxRetries)).runToList()
    }

    // then
    result shouldBe List(100)
    attemptCounter.get() shouldBe maxRetries + 1
    elapsedTime.toMillis should be >= (maxRetries * interval.toMillis)

  it should "not retry a flow which fails downstream" in:
    // given
    val upstreamInvocationCounter = new AtomicInteger(0)
    val downstreamInvocationCounter = new AtomicInteger(0)

    val flow = Flow
      .fromValues(1, 2, 3)
      .tap(_ => upstreamInvocationCounter.incrementAndGet().discard)
      .retry(Schedule.immediate.maxRetries(3))
      .tap { value =>
        downstreamInvocationCounter.incrementAndGet().discard
        if value == 2 then throw new RuntimeException("downstream failure")
      }

    // when
    val result = intercept[RuntimeException](flow.runToList())

    // then
    upstreamInvocationCounter.get() shouldBe 3 // 1, 2, 3
    downstreamInvocationCounter.get() shouldBe 2 // 1, 2
    result.getMessage() shouldBe "downstream failure"

  it should "fail after exhausting all retry attempts" in:
    // given
    val attemptCounter = new AtomicInteger(0)
    val maxRetries = 3
    val errorMessage = "persistent failure"

    val flow = Flow.usingEmit[Int] { _ =>
      attemptCounter.incrementAndGet()
      throw new RuntimeException(errorMessage)
    }

    // when/then
    val exception = the[ChannelClosedException.Error] thrownBy {
      flow.retry(Schedule.immediate.maxRetries(maxRetries)).runToList()
    }

    exception.getCause.getMessage shouldBe errorMessage
    attemptCounter.get() shouldBe maxRetries + 1

  it should "use custom ResultPolicy to determine retry worthiness" in:
    // given
    val attemptCounter = new AtomicInteger(0)
    val maxRetries = 3
    val fatalErrorMessage = "fatal error"
    val retryableErrorMessage = "retryable error"

    val flow = Flow.usingEmit[Int] { emit =>
      val attempt = attemptCounter.incrementAndGet()
      if attempt == 1 then throw new RuntimeException(retryableErrorMessage)
      else if attempt == 2 then throw new RuntimeException(fatalErrorMessage)
      else emit(50)
    }

    val config = RetryConfig[Throwable, Unit](
      Schedule.immediate.maxRetries(maxRetries),
      ResultPolicy.retryWhen[Throwable, Unit](_.getMessage != fatalErrorMessage)
    )

    // when/then
    val exception = the[ChannelClosedException.Error] thrownBy {
      flow.retry(config).runToList()
    }

    exception.getCause.getMessage shouldBe fatalErrorMessage
    attemptCounter.get() shouldBe 2 // Should stop after fatal error, not retry

  it should "handle empty flows correctly" in:
    // given
    val flow = Flow.empty[Int]

    // when
    val result = flow.retry(Schedule.immediate.maxRetries(3)).runToList()

    // then
    result shouldBe List.empty

  it should "handle flows that complete successfully on first attempt" in:
    // given
    val invocationCounter = new AtomicInteger(0)
    val flow = Flow.usingEmit[String] { emit =>
      invocationCounter.incrementAndGet()
      emit("first try success")
    }

    // when
    val result = flow.retry(Schedule.immediate.maxRetries(5)).runToList()

    // then
    result shouldBe List("first try success")
    invocationCounter.get() shouldBe 1 // Should only run once

  it should "retry the entire flow when processing fails" in:
    // given
    val invocationCounter = new AtomicInteger(0)
    val flow = Flow.fromValues(1, 2, 3).map { value =>
      val invocation = invocationCounter.incrementAndGet()
      if invocation == 1 then throw new RuntimeException("processing error")
      else value * 2
    }

    // when
    val result = flow.retry(Schedule.immediate.maxRetries(2)).runToList()

    // then
    result shouldBe List(2, 4, 6)
    invocationCounter.get() shouldBe 4 // First attempt fails on element 1, second attempt processes all 3 elements

  it should "work with complex flows containing transformations" in:
    // given
    val invocationCounter = new AtomicInteger(0)
    val flow = Flow
      .fromValues(1, 2, 3, 4, 5)
      .filter(_ % 2 == 0) // Keep only even numbers: 2, 4
      .map { value =>
        val invocation = invocationCounter.incrementAndGet()
        if invocation == 1 then throw new RuntimeException("transformation failed")
        else value * 10
      }

    // when
    val result = flow.retry(Schedule.immediate.maxRetries(1)).runToList()

    // then
    result shouldBe List(20, 40)
    invocationCounter.get() shouldBe 3 // First attempt fails on element 2, second attempt processes both filtered elements (2, 4)

  it should "not retry a flow which uses .take and control exceptions" in:
    // given
    val invocationCounter = new AtomicInteger(0)

    val flow =
      Flow.fromValues(1 to 10*).tap(_ => invocationCounter.incrementAndGet().discard).take(3).retry(Schedule.immediate.maxRetries(3))

    // when
    val result = flow.runToList()

    // then
    result shouldBe List(1, 2, 3)
    invocationCounter.get() shouldBe 3

end FlowOpsRetryTest
