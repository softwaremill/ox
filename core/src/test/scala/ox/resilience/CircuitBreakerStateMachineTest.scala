package ox.resilience

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import ox.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

class CircuitBreakerStateMachineTest extends AnyFlatSpec with Matchers:

  behavior of "Circuit Breaker state machine"

  it should "keep closed with healthy metrics" in supervised {
    // given
    val config = defaultConfig
    val stateMachine = CircuitBreakerStateMachine(config)
    val lastResult: Option[AcquireResult] = None
    val metrics =
      Metrics(hundredPercentSuccessRate, hundredPercentSuccessRate, config.minimumNumberOfCalls, lastResult, System.currentTimeMillis())

    // when
    val resultingState = CircuitBreakerStateMachine.nextState(metrics, CircuitBreakerState.Closed, stateMachine.config)

    resultingState shouldBe CircuitBreakerState.Closed
  }

  it should "go to open after surpasing failure threshold" in supervised {
    // given
    val config = defaultConfig
    val stateMachine = CircuitBreakerStateMachine(config)
    val lastResult: Option[AcquireResult] = None
    val metrics = Metrics(badFailureRate, hundredPercentSuccessRate, config.minimumNumberOfCalls, lastResult, System.currentTimeMillis())

    // when
    val resultingState = CircuitBreakerStateMachine.nextState(metrics, CircuitBreakerState.Closed, stateMachine.config)

    // then
    resultingState shouldBe a[CircuitBreakerState.Open]
  }

  it should "go straight to half open after surpasing failure threshold with defined waitDurationOpenState = 0" in supervised {
    // given
    val config = defaultConfig.copy(waitDurationOpenState = FiniteDuration(0, TimeUnit.MILLISECONDS))
    val stateMachine = CircuitBreakerStateMachine(config)
    val lastResult: Option[AcquireResult] = None
    val metrics = Metrics(badFailureRate, hundredPercentSuccessRate, config.minimumNumberOfCalls, lastResult, System.currentTimeMillis())

    // when
    val resultingState = CircuitBreakerStateMachine.nextState(metrics, CircuitBreakerState.Closed, stateMachine.config)

    // then
    resultingState shouldBe a[CircuitBreakerState.HalfOpen]
  }

  it should "go back to open after timeout in half open passed" in supervised {
    // given
    val config = defaultConfig.copy(halfOpenTimeoutDuration = FiniteDuration(10, TimeUnit.SECONDS))
    val stateMachine = CircuitBreakerStateMachine(config)
    val lastResult: Option[AcquireResult] = None
    val timestamp = System.currentTimeMillis()
    val metrics = Metrics(
      badFailureRate,
      hundredPercentSuccessRate,
      config.minimumNumberOfCalls,
      lastResult,
      timestamp + 15.seconds.toMillis // after timeout
    )

    // when
    val resultingState =
      CircuitBreakerStateMachine.nextState(metrics, CircuitBreakerState.HalfOpen(timestamp, Semaphore(10), 0), stateMachine.config)

    // then
    resultingState shouldBe a[CircuitBreakerState.Open]
  }

  it should "update counter of completed operations in halfopen state" in supervised {
    // given
    val config = defaultConfig
    val stateMachine = CircuitBreakerStateMachine(config)
    val completedCalls = 0
    val timestamp = System.currentTimeMillis()
    val state: CircuitBreakerState.HalfOpen = CircuitBreakerState.HalfOpen(timestamp, Semaphore(10), completedCalls)
    val lastResult: Option[AcquireResult] = Some(AcquireResult(true, state))
    val metrics = Metrics(badFailureRate, hundredPercentSuccessRate, config.minimumNumberOfCalls, lastResult, timestamp)

    // when
    val resultingState = CircuitBreakerStateMachine.nextState(metrics, state, stateMachine.config)

    // then
    resultingState shouldBe a[CircuitBreakerState.HalfOpen]
    resultingState.asInstanceOf[CircuitBreakerState.HalfOpen].completedOperations shouldBe state.completedOperations + 1
  }

  it should "go back to closed after enough calls with good metrics are recorded" in supervised {
    // given
    val config = defaultConfig
    val stateMachine = CircuitBreakerStateMachine(config)
    val completedCalls = config.numberOfCallsInHalfOpenState
    val timestamp = System.currentTimeMillis()
    val state = CircuitBreakerState.HalfOpen(timestamp, Semaphore(0), completedCalls)
    val lastResult: Option[AcquireResult] = Some(AcquireResult(true, state))
    val metrics = Metrics(hundredPercentSuccessRate, hundredPercentSuccessRate, config.numberOfCallsInHalfOpenState, lastResult, timestamp)

    // when
    val resultingState = CircuitBreakerStateMachine.nextState(metrics, state, stateMachine.config)

    // then
    resultingState shouldBe CircuitBreakerState.Closed
  }

  it should "go to open after enough calls with bad metrics are recorded" in supervised {
    // given
    val config = defaultConfig
    val stateMachine = CircuitBreakerStateMachine(config)
    val completedCalls = config.numberOfCallsInHalfOpenState
    val timestamp = System.currentTimeMillis()
    val state = CircuitBreakerState.HalfOpen(timestamp, Semaphore(0), completedCalls)
    val lastResult: Option[AcquireResult] = Some(AcquireResult(true, state))
    val metrics = Metrics(badFailureRate, hundredPercentSuccessRate, config.numberOfCallsInHalfOpenState, lastResult, timestamp)

    // when
    val resultingState = CircuitBreakerStateMachine.nextState(metrics, state, stateMachine.config)

    // then
    resultingState shouldBe a[CircuitBreakerState.Open]
  }

  it should "go to half open after waitDurationOpenState passes" in supervised {
    // given
    val config = defaultConfig
    val stateMachine = CircuitBreakerStateMachine(config)
    val currentTimestamp = System.currentTimeMillis()
    val state = CircuitBreakerState.Open(currentTimestamp)
    val lastResult: Option[AcquireResult] = None
    val metrics =
      Metrics(
        badFailureRate,
        hundredPercentSuccessRate,
        config.minimumNumberOfCalls,
        lastResult,
        currentTimestamp + 15.seconds.toMillis // after wait time
      )

    // when
    val resultingState = CircuitBreakerStateMachine.nextState(metrics, state, stateMachine.config)

    // then
    resultingState shouldBe a[CircuitBreakerState.HalfOpen]
  }

  private val defaultConfig: CircuitBreakerConfig =
    CircuitBreakerConfig(
      failureRateThreshold = 50,
      slowCallThreshold = 50,
      slowCallDurationThreshold = 60.seconds,
      slidingWindow = SlidingWindow.CountBased(100),
      minimumNumberOfCalls = 20,
      waitDurationOpenState = FiniteDuration(10, java.util.concurrent.TimeUnit.SECONDS),
      halfOpenTimeoutDuration = FiniteDuration(0, TimeUnit.MILLISECONDS),
      numberOfCallsInHalfOpenState = 10
    )

  private val hundredPercentSuccessRate = 0
  private val badFailureRate = 100

end CircuitBreakerStateMachineTest
