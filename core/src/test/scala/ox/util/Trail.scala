package ox.util

import java.time.Clock

class Trail(var trail: Vector[String] = Vector.empty) {
  def add(s: String): Unit = {
    println(s"[${Clock.systemUTC().instant()}] [${Thread.currentThread().threadId()}] $s")
    trail = trail :+ s
  }
}
