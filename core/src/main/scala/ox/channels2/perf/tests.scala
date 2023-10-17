package ox.channels2.perf

@main def run(): Unit =
  for (i <- 1 to 3) {
    println(s"Run $i")
    //usingOx()
    passingValues()
  }
