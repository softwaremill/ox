package ox.resilience

import scala.concurrent.duration.*
import ox.*
import java.util.concurrent.Semaphore
import ox.channels.ActorRef
import ox.resilience.CircuitBreakerStateMachine.nextState
import scala.compiletime.ops.double

private[resilience] case class CircuitBreakerStateMachine(
    config: CircuitBreakerStateMachineConfig,
    private val results: CircuitBreakerResults
)(using val ox: Ox):
  @volatile private var _state: CircuitBreakerState = CircuitBreakerState.Closed

  def state: CircuitBreakerState = _state

  def registerResult(result: CircuitBreakerResult, acquired: AcquireResult, selfRef: ActorRef[CircuitBreakerStateMachine]): Unit =
    results.updateResults(result)
    updateState(selfRef, Some(acquired))
  end registerResult

  def updateState(selfRef: ActorRef[CircuitBreakerStateMachine], acquiredResult: Option[AcquireResult] = None): Unit =
    val oldState = _state
    val newState = nextState(results.calculateMetrics(None, System.currentTimeMillis()), oldState, config)
    _state = newState
    scheduleCallback(oldState, newState, selfRef)
    results.onStateChange(oldState, newState)

  private def scheduleCallback(
      oldState: CircuitBreakerState,
      newState: CircuitBreakerState,
      selfRef: ActorRef[CircuitBreakerStateMachine]
  ): Unit =
    (oldState, newState) match
      case (CircuitBreakerState.Closed, CircuitBreakerState.Open(_)) =>
        // schedule switch to halfOpen after timeout
        updateAfter(config.waitDurationOpenState, selfRef)
      case (CircuitBreakerState.Open(_), CircuitBreakerState.HalfOpen(since, semaphore, completedOperations)) =>
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
    val lastAcquireResult = metrics.lastAcquisitionResult.filter(_.acquired)
    val exceededThreshold = (metrics.failureRate >= config.failureRateThreshold || metrics.slowCallsRate >= config.slowCallThreshold)
    val minCallsRecorder = metrics.operationsInWindow >= config.minimumNumberOfCalls
    currentState match
      case CircuitBreakerState.Closed =>
        if minCallsRecorder && exceededThreshold then
          if config.waitDurationOpenState.toMillis == 0 then
            CircuitBreakerState.HalfOpen(currentTimestamp, Semaphore(config.numberOfCallsInHalfOpenState))
          else CircuitBreakerState.Open(currentTimestamp)
        else CircuitBreakerState.Closed
      case CircuitBreakerState.Open(since) =>
        val timePassed = (currentTimestamp - since) > config.waitDurationOpenState.toMillis
        if timePassed || config.waitDurationOpenState.toMillis == 0 then
          CircuitBreakerState.HalfOpen(currentTimestamp, Semaphore(config.numberOfCallsInHalfOpenState))
        else CircuitBreakerState.Open(since)
      case CircuitBreakerState.HalfOpen(since, semaphore, completedCalls) =>
        lazy val allCallsInHalfOpenCompleted = completedCalls >= config.numberOfCallsInHalfOpenState
        lazy val timePassed = (currentTimestamp - since) > config.halfOpenTimeoutDuration.toMillis
        // if we didn't complete all half open calls but timeout is reached go back to open
        if !allCallsInHalfOpenCompleted && config.halfOpenTimeoutDuration.toMillis != 0 && timePassed then
          CircuitBreakerState.Open(currentTimestamp)
        // If halfOpen calls were completed && rates are below we close breaker
        else if allCallsInHalfOpenCompleted && !exceededThreshold then CircuitBreakerState.Closed
        // If halfOpen calls completed, but rates are still above go back to open
        else if allCallsInHalfOpenCompleted && exceededThreshold then CircuitBreakerState.Open(currentTimestamp)
        // We didn't complete all half open calls, keep halfOpen
        else
          lastAcquireResult match
            case Some(AcquireResult(true, CircuitBreakerState.HalfOpen(s, sem, completed))) =>
              CircuitBreakerState.HalfOpen(s, sem, completed + 1)
            case _ => CircuitBreakerState.HalfOpen(since, semaphore, completedCalls)
        end if
    end match
  end nextState

end CircuitBreakerStateMachine

private[resilience] sealed trait CircuitBreakerResults(using val ox: Ox):
  def onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState): Unit
  def updateResults(result: CircuitBreakerResult): Unit
  def calculateMetrics(lastAcquisitionResult: Option[AcquireResult], timestamp: Long): Metrics

private[resilience] object CircuitBreakerResults:
  case class CountBased(windowSize: Int)(using ox: Ox) extends CircuitBreakerResults(using ox):
    private val results = new collection.mutable.ArrayDeque[CircuitBreakerResult](windowSize)
    private var slowCalls = 0
    private var failedCalls = 0
    private var successCalls = 0

    private def clearResults: Unit =
      results.clear()
      slowCalls = 0
      failedCalls = 0
      successCalls = 0

    def onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState): Unit =
      import CircuitBreakerState.*
      // we have to match so we don't reset result when for example incrementing completed calls in halfopen state
      (oldState, newState) match
        case (Closed, Open(_) | HalfOpen(_, _, _)) =>
          clearResults
        case (HalfOpen(_, _, _), Open(_) | Closed) =>
          clearResults
        case (Open(_), Closed | HalfOpen(_, _, _)) =>
          clearResults
        case (_, _) => ()
      end match
    end onStateChange

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
      val failuresRate = ((failedCalls / numOfOperations.toFloat) * 100).toInt
      val slowRate = ((slowCalls / numOfOperations.toFloat) * 100).toInt
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
    // holds timestamp of recored operation and result
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
      // filter all entries that happend outside sliding window
      val res = results.filterInPlace { (time, result) =>
        val isOlder = timestamp > time + windowDuration.toMillis
        if isOlder then
          result match
            case CircuitBreakerResult.Success => successCalls -= 1
            case CircuitBreakerResult.Failure => failedCalls -= 1
            case CircuitBreakerResult.Slow    => slowCalls -= 1
        isOlder
      }
      val numOfOperations = res.length
      val failuresRate = ((failedCalls / numOfOperations.toFloat) * 100).toInt
      val slowRate = ((slowCalls / numOfOperations.toFloat) * 100).toInt
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
      import CircuitBreakerState.*
      // we have to match so we don't reset result when for example incrementing completed calls in halfopen state
      (oldState, newState) match
        case (Closed, Open(_) | HalfOpen(_, _, _)) =>
          clearResults()
        case (HalfOpen(_, _, _), Open(_) | Closed) =>
          clearResults()
        case (Open(_), Closed | HalfOpen(_, _, _)) =>
          clearResults()
        case (_, _) => ()
      end match
    end onStateChange
  end TimeWindowBased
end CircuitBreakerResults
