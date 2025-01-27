package ox.resilience

import scala.concurrent.duration.*
import ox.*
import java.util.concurrent.Semaphore
import ox.channels.ActorRef
import ox.resilience.CircuitBreakerStateMachine.nextState

private[resilience] case class CircuitBreakerStateMachine(
    config: CircuitBreakerStateMachineConfig,
    private val results: CircuitBreakerResults
)(using val ox: Ox):
  @volatile private var _state: CircuitBreakerState = CircuitBreakerState.Closed(System.currentTimeMillis())

  def state: CircuitBreakerState = _state

  def registerResult(result: CircuitBreakerResult, acquired: AcquireResult, selfRef: ActorRef[CircuitBreakerStateMachine]): Unit =
    // If acquired in different state we don't update results
    if acquired.circuitState.isSameState(_state) then
      results.updateResults(result)
      updateState(selfRef, Some(acquired))
  end registerResult

  def updateState(selfRef: ActorRef[CircuitBreakerStateMachine], acquiredResult: Option[AcquireResult] = None): Unit =
    val oldState = _state
    val newState = nextState(results.calculateMetrics(acquiredResult, System.currentTimeMillis()), oldState, config)
    _state = newState
    scheduleCallback(oldState, newState, selfRef)
    results.onStateChange(oldState, newState)

  private def scheduleCallback(
      oldState: CircuitBreakerState,
      newState: CircuitBreakerState,
      selfRef: ActorRef[CircuitBreakerStateMachine]
  ): Unit =
    (oldState, newState) match
      case (_, CircuitBreakerState.Open(_)) =>
        // schedule switch to halfOpen after timeout
        updateAfter(config.waitDurationOpenState, selfRef)
      case (
            CircuitBreakerState.Open(_) | CircuitBreakerState.Closed(_),
            CircuitBreakerState.HalfOpen(_, _, _)
          ) =>
        // schedule timeout for halfOpen state if is not 0
        if config.halfOpenTimeoutDuration.toMillis != 0 then updateAfter(config.halfOpenTimeoutDuration, selfRef)
      case _ => ()

  private def updateAfter(after: FiniteDuration, actorRef: ActorRef[CircuitBreakerStateMachine]): Unit =
    forkDiscard:
      sleep(after)
      actorRef.tell(_.updateState(actorRef))

end CircuitBreakerStateMachine

private[resilience] object CircuitBreakerStateMachine:
  def apply(config: CircuitBreakerConfig)(using
      Ox
  ): CircuitBreakerStateMachine =
    config.slidingWindow match
      case SlidingWindow.CountBased(size) =>
        CircuitBreakerStateMachine(
          CircuitBreakerStateMachineConfig.fromConfig(config),
          CircuitBreakerResults.CountBased(size)
        )
      case SlidingWindow.TimeBased(duration) =>
        CircuitBreakerStateMachine(
          CircuitBreakerStateMachineConfig.fromConfig(config),
          CircuitBreakerResults.TimeWindowBased(duration)
        )
  end apply

  def nextState(metrics: Metrics, currentState: CircuitBreakerState, config: CircuitBreakerStateMachineConfig): CircuitBreakerState =
    val currentTimestamp = metrics.timestamp
    val exceededThreshold =
      config.failureRateThreshold.isExceeded(metrics.failureRate) || config.slowCallThreshold.isExceeded(metrics.slowCallsRate)
    val minCallsRecorder = metrics.operationsInWindow >= config.minimumNumberOfCalls
    currentState match
      case self @ CircuitBreakerState.Closed(_) =>
        if minCallsRecorder && exceededThreshold then
          if config.waitDurationOpenState.toMillis == 0 then
            CircuitBreakerState.HalfOpen(currentTimestamp, Semaphore(config.numberOfCallsInHalfOpenState))
          else CircuitBreakerState.Open(currentTimestamp)
        else self
      case self @ CircuitBreakerState.Open(since) =>
        val timePassed = (currentTimestamp - since) >= config.waitDurationOpenState.toMillis
        if timePassed then CircuitBreakerState.HalfOpen(currentTimestamp, Semaphore(config.numberOfCallsInHalfOpenState))
        else self
      case CircuitBreakerState.HalfOpen(since, semaphore, completedCalls) =>
        // We want to know if last result should be added to completed calls in halfOpen state
        val lastCompletedCall = if metrics.lastAcquisitionResult.isDefined then 1 else 0
        val allCallsInHalfOpenCompleted = (completedCalls + lastCompletedCall) >= config.numberOfCallsInHalfOpenState
        val timePassed = (currentTimestamp - since) >= config.halfOpenTimeoutDuration.toMillis
        // if we didn't complete all half open calls but timeout is reached go back to open
        if !allCallsInHalfOpenCompleted && config.halfOpenTimeoutDuration.toMillis != 0 && timePassed then
          CircuitBreakerState.Open(currentTimestamp)
        // If halfOpen calls were completed && rates are below we close breaker
        else if allCallsInHalfOpenCompleted && !exceededThreshold then CircuitBreakerState.Closed(currentTimestamp)
        // If halfOpen calls completed, but rates are still above go back to open
        else if allCallsInHalfOpenCompleted && exceededThreshold then CircuitBreakerState.Open(currentTimestamp)
        // We didn't complete all half open calls, keep halfOpen
        else CircuitBreakerState.HalfOpen(since, semaphore, completedCalls + lastCompletedCall)
        end if
    end match
  end nextState

end CircuitBreakerStateMachine

private[resilience] sealed trait CircuitBreakerResults(using val ox: Ox):
  def onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState): Unit
  def updateResults(result: CircuitBreakerResult): Unit
  def calculateMetrics(lastAcquisitionResult: Option[AcquireResult], timestamp: Long): Metrics

private[resilience] object CircuitBreakerResults:
  private object Percentage:
    def of(observed: Int, size: Int): Int = ((observed / size.toFloat) * 100).toInt

  case class CountBased(windowSize: Int)(using ox: Ox) extends CircuitBreakerResults(using ox):
    private val results = new collection.mutable.ArrayDeque[CircuitBreakerResult](windowSize + 1)
    private var slowCalls = 0
    private var failedCalls = 0
    private var successCalls = 0

    private def clearResults(): Unit =
      results.clear()
      slowCalls = 0
      failedCalls = 0
      successCalls = 0

    def onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState): Unit =
      if !oldState.isSameState(newState) then clearResults()

    def updateResults(result: CircuitBreakerResult): Unit =
      result match
        case CircuitBreakerResult.Success => successCalls += 1
        case CircuitBreakerResult.Failure => failedCalls += 1
        case CircuitBreakerResult.Slow    => slowCalls += 1
      val resultingQueue = results.addOne(result)
      if resultingQueue.length > windowSize then
        resultingQueue.removeHeadOption(false) match
          case Some(CircuitBreakerResult.Success) => successCalls -= 1
          case Some(CircuitBreakerResult.Failure) => failedCalls -= 1
          case Some(CircuitBreakerResult.Slow)    => slowCalls -= 1
          case None                               => ()
    end updateResults

    def calculateMetrics(lastAcquisitionResult: Option[AcquireResult], timestamp: Long): Metrics =
      val numOfOperations = results.length
      val failuresRate = Percentage.of(failedCalls, numOfOperations)
      val slowRate = Percentage.of(slowCalls, numOfOperations)
      Metrics(
        failuresRate,
        slowRate,
        numOfOperations,
        lastAcquisitionResult,
        timestamp
      )
    end calculateMetrics
  end CountBased

  case class TimeWindowBased(windowDuration: FiniteDuration)(using ox: Ox) extends CircuitBreakerResults(using ox):
    // holds timestamp of recorded operation and result
    private val results = collection.mutable.ArrayDeque[(Long, CircuitBreakerResult)]()
    private var slowCalls = 0
    private var failedCalls = 0
    private var successCalls = 0

    private def clearResults(): Unit =
      results.clear()
      slowCalls = 0
      failedCalls = 0
      successCalls = 0

    def calculateMetrics(lastAcquisitionResult: Option[AcquireResult], timestamp: Long): Metrics =
      // filter all entries that happened outside sliding window
      val removed = results.removeHeadWhile((time, _) => timestamp > time + windowDuration.toMillis)
      removed.foreach { (_, result) =>
        result match
          case CircuitBreakerResult.Success => successCalls -= 1
          case CircuitBreakerResult.Failure => failedCalls -= 1
          case CircuitBreakerResult.Slow    => slowCalls -= 1
      }
      val numOfOperations = results.length
      val failuresRate = Percentage.of(failedCalls, numOfOperations)
      val slowRate = Percentage.of(slowCalls, numOfOperations)
      Metrics(
        failuresRate,
        slowRate,
        numOfOperations,
        lastAcquisitionResult,
        timestamp
      )
    end calculateMetrics

    def updateResults(result: CircuitBreakerResult): Unit =
      result match
        case CircuitBreakerResult.Success => successCalls += 1
        case CircuitBreakerResult.Failure => failedCalls += 1
        case CircuitBreakerResult.Slow    => slowCalls += 1
      results.addOne((System.currentTimeMillis(), result))

    def onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState): Unit =
      if !oldState.isSameState(newState) then clearResults()

  end TimeWindowBased
end CircuitBreakerResults
