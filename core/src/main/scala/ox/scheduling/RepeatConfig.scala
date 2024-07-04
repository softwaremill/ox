package ox.scheduling

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt

case class RepeatConfig[E, T](
    schedule: Schedule,
    shouldContinueOnError: E => Boolean = (_: E) => false,
    shouldContinue: T => Boolean = (_: T) => true
)

object RepeatConfig:
  def immediate[E, T](repeats: Int): RepeatConfig[E, T] = RepeatConfig(Schedule.Immediate(repeats))
  def immediateForever[E, T]: RepeatConfig[E, T] = RepeatConfig(Schedule.Immediate.forever)

  def delay[E, T](repeats: Int, delay: FiniteDuration): RepeatConfig[E, T] = RepeatConfig(Schedule.Delay(repeats, delay))
  def delayForever[E, T](delay: FiniteDuration): RepeatConfig[E, T] = RepeatConfig(Schedule.Delay.forever(delay))

  def fixedRate[E, T](repeats: Int, interval: FiniteDuration): RepeatConfig[E, T] = RepeatConfig(Schedule.FixedRate(repeats, interval))
  def fixedRateForever[E, T](interval: FiniteDuration): RepeatConfig[E, T] = RepeatConfig(Schedule.FixedRate.forever(interval))

  def exponential[E, T](
      repeats: Int,
      firstDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ): RepeatConfig[E, T] = RepeatConfig(Schedule.Exponential(repeats, firstDelay, maxDelay, jitter))

  def exponentialForever[E, T](
      firstDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ): RepeatConfig[E, T] = RepeatConfig(Schedule.Exponential.forever(firstDelay, maxDelay, jitter))
