package ox.util

import java.util.concurrent.atomic.AtomicInteger

class MaxCounter():
  val counter = new AtomicInteger(0)
  @volatile var max = 0

  def increment() =
    counter.updateAndGet { c =>
      val inc = c + 1
      max = if inc > max then inc else max
      inc
    }

  def decrement() =
    counter.decrementAndGet()
end MaxCounter
