package ox.channels2.perf

def timed[T](label: String)(t: => T): T =
  val start = System.currentTimeMillis()
  val r = t
  val end = System.currentTimeMillis()
  println(s"$label Took ${end - start}ms")
  r

def sumUpTo(n: Int): Long = n.toLong * (n + 1) / 2
