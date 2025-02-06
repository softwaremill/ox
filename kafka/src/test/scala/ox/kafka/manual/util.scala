package ox.kafka.manual

import scala.util.Random
import ox.timed

def timedAndLogged[T](name: String)(f: => T): T =
  val (took, result) = timed(f)
  println(s"$name took ${took.toMillis}ms")
  result

def randomString() = Random().alphanumeric.take(100).mkString
