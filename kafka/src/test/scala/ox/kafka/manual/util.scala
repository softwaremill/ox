package ox.kafka.manual

import scala.util.Random

def timed[T](name: String)(f: => T): T = {
  val start = System.currentTimeMillis()
  val result = f
  val end = System.currentTimeMillis()
  println(s"$name took ${end - start}ms")
  result
}

def randomString() = Random().alphanumeric.take(100).mkString
