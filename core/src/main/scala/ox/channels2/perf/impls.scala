package ox.channels2.perf

import ox.*
import ox.channels2.*

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.atomic.{AtomicLong, AtomicReferenceArray}

// transmitting 10m values between two forks, using the experimental rendezvous channels
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

// pass 10m values between two forks, using a synchronous queue and an "infinite array"
def passingValues(): Unit =
  val max = 10_000_000
  val st = new AtomicReferenceArray[SynchronousQueue[String]](max)
  val s = new AtomicLong(0)
  val r = new AtomicLong(0)
  timed("passingValues") {
    supervised {
      fork {
        for (i <- 0 until max) {
          s.incrementAndGet()
          r.get()
          val q = new SynchronousQueue[String]()
          val qq =
            if st.compareAndSet(i, null, q) then q.take()
            else st.get(i).put("sender")
        }
      }

      fork {
        for (i <- 0 until max) {
          r.incrementAndGet()
          s.get()
          val q = new SynchronousQueue[String]()
          val qq =
            if st.compareAndSet(i, null, q) then q.take()
            else st.get(i).put("receiver")
        }
      }.join()
    }
  }

// same as usingOx, but without scopes - pure virtual threads
def usingThreads(): Unit =
  val max = 10_000_000

  val c = Channel[Int]()
  timed(s"threads()") {
    val t1 = Thread
      .ofVirtual()
      .start(() =>
        var i = 0
        while i <= max do
          c.send(i)
          i += 1
      )

    val t2 = Thread
      .ofVirtual()
      .start(() =>
        var acc = 0L
        for (i <- 0 to max) {
          acc += c.receive()
        }
        ()
      )

    t1.join()
    t2.join()
  }
