package ox.resilience

import scala.concurrent.duration.*
import ox.*
import ox.scheduling.scheduled
import ox.scheduling.{ScheduledConfig, Schedule}
import java.util.concurrent.Semaphore
import ox.channels.ActorRef

private sealed trait CircuitBreakerStateMachine(val config: CircuitBreakerStateMachineConfig)(using val ox: Ox):
  def calculateMetrics(lastAcquisitionResult: Option[AcquireResult], timestamp: Long): Metrics
  def updateResults(result: CircuitBreakerResult): Unit
  def onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState): Unit

  @volatile private var _state: CircuitBreakerState = CircuitBreakerState.Closed

  def state: CircuitBreakerState = _state

  def registerResult(result: CircuitBreakerResult, acquired: AcquireResult, selfRef: ActorRef[CircuitBreakerStateMachine]): Unit =
    updateResults(result)
    val oldState = _state
    val newState = nextState(calculateMetrics(Some(acquired), System.currentTimeMillis()), oldState)
    _state = newState
    scheduleCallback(oldState, newState, selfRef)
    onStateChange(oldState, newState)
  end registerResult

  def updateState(selfRef: ActorRef[CircuitBreakerStateMachine]): Unit =
    val oldState = _state
    val newState = nextState(calculateMetrics(None, System.currentTimeMillis()), oldState)
    _state = newState
    scheduleCallback(oldState, newState, selfRef)
    onStateChange(oldState, newState)

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

  private def updateAfter(after: FiniteDuration, actorRef: ActorRef[CircuitBreakerStateMachine])(using Ox): Unit =
    forkDiscard:
      scheduled(ScheduledConfig(Schedule.InitialDelay(after)))(actorRef.tell(_.updateState(actorRef)))

  private[resilience] def nextState(metrics: Metrics, currentState: CircuitBreakerState): CircuitBreakerState =
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

private[resilience] object CircuitBreakerStateMachine:
  def apply(config: CircuitBreakerConfig)(using
      Ox
  ): CircuitBreakerStateMachine =
    config.slidingWindow match
      case SlidingWindow.CountBased(size) =>
        CircuitBreakerCountStateMachine(
          CircuitBreakerStateMachineConfig.fromConfig(config),
          size
        )
      case SlidingWindow.TimeBased(duration) =>
        CircuitBreakerTimeStateMachine(
          CircuitBreakerStateMachineConfig.fromConfig(config),
          duration
        )
  end apply

  private[resilience] case class CircuitBreakerCountStateMachine(
      stateMachineConfig: CircuitBreakerStateMachineConfig,
      windowSize: Int
  )(using ox: Ox)
      extends CircuitBreakerStateMachine(stateMachineConfig)(using ox):

    private val callResults: Array[Option[CircuitBreakerResult]] = Array.fill[Option[CircuitBreakerResult]](windowSize)(None)
    private var writeIndex = 0

    def onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState): Unit =
      import CircuitBreakerState.*
      // we have to match so we don't reset result when for example incrementing completed calls in halfopen state
      (oldState, newState) match
        case (Closed, Open(_) | HalfOpen(_, _, _)) =>
          callResults.mapInPlace(_ => None).discard
          writeIndex = 0
        case (HalfOpen(_, _, _), Open(_) | Closed) =>
          callResults.mapInPlace(_ => None).discard
          writeIndex = 0
        case (Open(_), Closed | HalfOpen(_, _, _)) =>
          callResults.mapInPlace(_ => None).discard
          writeIndex = 0
        case (_, _) => ()
      end match
    end onStateChange

    def updateResults(result: CircuitBreakerResult): Unit =
      callResults(writeIndex) = Some(result)
      writeIndex = (writeIndex + 1) % windowSize

    def calculateMetrics(lastAcquisitionResult: Option[AcquireResult], timestamp: Long): Metrics =
      val results = callResults.flatMap(identity)
      val numOfOperations = results.length
      val failuresRate = ((results.count(_ == CircuitBreakerResult.Failure) / windowSize.toFloat) * 100).toInt
      val slowRate = ((results.count(_ == CircuitBreakerResult.Slow) / windowSize.toFloat) * 100).toInt
      Metrics(
        failuresRate,
        slowRate,
        numOfOperations,
        lastAcquisitionResult,
        timestamp
      )
    end calculateMetrics
  end CircuitBreakerCountStateMachine

  private[resilience] case class CircuitBreakerTimeStateMachine(
      stateMachineConfig: CircuitBreakerStateMachineConfig,
      windowDuration: FiniteDuration
  )(using ox: Ox)
      extends CircuitBreakerStateMachine(stateMachineConfig)(using ox):

    // holds timestamp of recored operation and result
    private val queue = collection.mutable.Queue[(Long, CircuitBreakerResult)]()

    def calculateMetrics(lastAcquisitionResult: Option[AcquireResult], timestamp: Long): Metrics =
      // filter all entries that happend outside sliding window
      val results = queue.filter((time, _) => timestamp > time + windowDuration.toMillis)
      val numOfOperations = results.length
      val failuresRate = ((results.count(_ == CircuitBreakerResult.Failure) / results.length.toFloat) * 100).toInt
      val slowRate = ((results.count(_ == CircuitBreakerResult.Slow) / results.length.toFloat) * 100).toInt
      Metrics(
        failuresRate,
        slowRate,
        numOfOperations,
        lastAcquisitionResult,
        timestamp
      )
    end calculateMetrics
    def updateResults(result: CircuitBreakerResult): Unit =
      queue.addOne((System.currentTimeMillis(), result))
    def onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState): Unit =
      import CircuitBreakerState.*
      // we have to match so we don't reset result when for example incrementing completed calls in halfopen state
      (oldState, newState) match
        case (Closed, Open(_) | HalfOpen(_, _, _)) =>
          queue.clearAndShrink(config.minimumNumberOfCalls)
        case (HalfOpen(_, _, _), Open(_) | Closed) =>
          queue.clearAndShrink(config.minimumNumberOfCalls)
        case (Open(_), Closed | HalfOpen(_, _, _)) =>
          queue.clearAndShrink(config.minimumNumberOfCalls)
        case (_, _) => ()
      end match
    end onStateChange
  end CircuitBreakerTimeStateMachine
end CircuitBreakerStateMachine
