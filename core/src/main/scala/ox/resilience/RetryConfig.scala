package ox.resilience

import ox.scheduling.ScheduledConfig

trait RetryConfig[E, T]:
  def toScheduledConfig: ScheduledConfig[E, T]
