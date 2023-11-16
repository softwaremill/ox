package ox

import scala.concurrent.duration.*

trait ElapsedTime {
  
  def measure[T](f: => T): (T, Duration) =
    val before = System.currentTimeMillis()
    val result = f
    val after = System.currentTimeMillis();
    (result, (after - before).millis)
}
