package ox.util

import scala.concurrent.duration.*

trait ElapsedTime:
  def measure[T](f: => T): (T, Duration) =
    val before = System.nanoTime()
    val result = f
    val after = System.nanoTime()
    (result, (after - before).nanos)
