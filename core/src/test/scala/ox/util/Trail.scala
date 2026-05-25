package ox.util

import ox.discard

import java.util.concurrent.atomic.AtomicReference

class Trail(trail: AtomicReference[Vector[String]] = AtomicReference(Vector.empty)):
  def add(s: String): Unit =
    println(s"[${System.currentTimeMillis()}] [${Thread.currentThread().threadId()}] $s")
    trail.updateAndGet(_ :+ s).discard

  def get: Vector[String] = trail.get
