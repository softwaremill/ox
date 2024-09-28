package ox.flow

import ox.channels.Source
import ox.supervised

@main def test(): Unit = supervised:
  val ch = Source.fromValues((1 to 10)*)

  def inc(l: String): Int => Int = x => {
    println(s"${Thread.currentThread().threadId()} $l inc: $x")
    x + 1
  }

//   val ch2 = Flow.fromSource(ch).map(inc("A")).map(inc("B")).map(inc("C")).run() // .filter(_ % 3 == 0).run()
  val ch2 = Flow.fromSource(ch).map(inc("A")).map(inc("B")).async().map(inc("C")).runToChannel() // .filter(_ % 3 == 0).run()
  // val ch2 = Flow.fromSource(ch).map(inc("A")).map(x => throw new RuntimeException("b!")).async().map(inc("C")).run() // .filter(_ % 3 == 0).run()

  // Thread.sleep(2000)

  ch2.foreach(println)

@main def test2(): Unit =
  Flow
    .fromSender[Int] { send =>
      send(1)
      send(2)
      send(3)
      println("X")
      send(4)
      send(5)
    }
    .map(_ + 1)
    .runForeach(println)