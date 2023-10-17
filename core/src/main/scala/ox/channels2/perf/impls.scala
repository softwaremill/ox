package ox.channels2.perf

import ox.*
import ox.channels2.*

import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedDeque, Executors}
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.*

def usingOx(): Unit =
  val max = 10_000_000

  val c = Channel[Int]()
  timed(s"ox()") {
    supervised {
      fork {
        var i = 0
        while i <= max do
          c.send(i)
          i += 1
      }

      val f = fork {
        var acc = 0L
        for (i <- 0 to max) {
          acc += c.receive()
        }
        acc
      }

      assert(f.join() == sumUpTo(max))
    }
  }

def passingValues(): Unit =
  val max = 10_000_000
  val d = Vector.fill(max)(new ArrayBlockingQueue[String](1))
  timed("passingValues") {
    supervised {
      fork {
        for (i <- 0 until max) {
          d(i).put("")
        }
      }

      fork {
        for (i <- 0 until max) {
          d(i).take()
        }
      }
    }
  }
