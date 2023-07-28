package ox.util

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

class Trail(trail: AtomicReference[Vector[String]] = AtomicReference(Vector.empty)) {
  def add(s: String): Unit = {
    println(s"[${Clock.systemUTC().instant()}] [${Thread.currentThread().threadId()}] $s")
    trail.updateAndGet(_ :+ s)
  }

  def get: Vector[String] = trail.get
}
