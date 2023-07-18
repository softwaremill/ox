package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

class SourceOpsTest extends AnyFlatSpec with Matchers with Eventually {
  it should "map over a source" in {
    scoped {
      val c = Channel[Int]()
      fork {
        c.send(1)
        c.send(2)
        c.send(3)
        c.done()
      }

      val s = c.map(_ * 10)

      s.receive() shouldBe 10
      s.receive() shouldBe 20
      s.receive() shouldBe 30
      s.receive() shouldBe ChannelClauseResult.Done
    }
  }

  it should "map over a source (stress test)" in {
    // this demonstrated a race condition where a cell was added by select to the waiting list by T1, completed by T2,
    // which then subsequently completed the stream; only then T1 wakes up, and checks if no new elements have been added
    for (_ <- 1 to 100000) {
      scoped {
        val c = Channel[Int]()
        fork {
          c.send(1)
          c.done()
        }

        val s = c.map(_ * 10)

        s.receive() shouldBe 10
        s.receive() shouldBe ChannelClauseResult.Done
      }
    }
  }

  it should "map over a source using for-syntax" in {
    scoped {
      val c = Channel[Int]()
      fork {
        c.send(1)
        c.send(2)
        c.send(3)
        c.done()
      }

      val s = for {
        v <- c
      } yield v * 2

      s.receive() shouldBe 2
      s.receive() shouldBe 4
      s.receive() shouldBe 6
      s.receive() shouldBe ChannelClauseResult.Done
    }
  }

  it should "iterate over a source" in {
    val c = Channel[Int](10)
    c.send(1)
    c.send(2)
    c.send(3)
    c.done()

    var r: List[Int] = Nil
    c.foreach(v => r = v :: r)

    r shouldBe List(3, 2, 1)
  }

  it should "iterate over a source using for-syntax" in {
    val c = Channel[Int](10)
    c.send(1)
    c.send(2)
    c.send(3)
    c.done()

    var r: List[Int] = Nil
    for {
      v <- c
    } r = v :: r

    r shouldBe List(3, 2, 1)
  }

  it should "convert source to a list" in {
    val c = Channel[Int](10)
    c.send(1)
    c.send(2)
    c.send(3)
    c.done()

    c.toList shouldBe List(1, 2, 3)
  }

  it should "transform a source using a simple map" in {
    val c = Channel[Int](10)
    c.send(1)
    c.send(2)
    c.send(3)
    c.done()

    scoped {
      c.transform(_.map(_ * 2)).toList shouldBe List(2, 4, 6)
    }
  }

  it should "transform a source using a complex chain of operations" in {
    val c = Channel[Int](10)
    c.send(1)
    c.send(2)
    c.send(3)
    c.send(4)
    c.done()

    scoped {
      c.transform(_.drop(2).flatMap(i => List(i, i + 1, i + 2)).filter(_ % 2 == 0)).toList shouldBe List(4, 4, 6)
    }
  }

  it should "transform an infinite source" in {
    val c = Channel[Int]()
    scoped {
      fork {
        var i = 0
        while true do
          c.send(i)
          i += 1
      }

      val s = c.transform(_.filter(_ % 2 == 0).flatMap(i => List(i, i + 1)))
      s.receive() shouldBe 0
      s.receive() shouldBe 1
      s.receive() shouldBe 2
    }
  }

  it should "transform an infinite source (stress test)" in {
    for (_ <- 1 to 1000) { // this nicely demonstrated two race conditions
      val c = Channel[Int]()
      scoped {
        fork {
          var i = 0
          while true do
            c.send(i)
            i += 1
        }

        val s = c.transform(x => x)
        s.receive() shouldBe 0
      }
    }
  }

  it should "tick regularly" in {
    scoped {
      val c = Source.tick(100.millis)
      val start = System.currentTimeMillis()
      c.receive() shouldBe ()
      (System.currentTimeMillis() - start) shouldBe >=(0L)
      (System.currentTimeMillis() - start) shouldBe <=(50L)

      c.receive() shouldBe ()
      (System.currentTimeMillis() - start) shouldBe >=(100L)
      (System.currentTimeMillis() - start) shouldBe <=(150L)

      c.receive() shouldBe ()
      (System.currentTimeMillis() - start) shouldBe >=(200L)
      (System.currentTimeMillis() - start) shouldBe <=(250L)
    }
  }

  it should "timeout" in {
    scoped {
      val c = Source.timeout(100.millis)
      val start = System.currentTimeMillis()
      c.receive() shouldBe ()
      (System.currentTimeMillis() - start) shouldBe >=(100L)
      (System.currentTimeMillis() - start) shouldBe <=(150L)
    }
  }

  it should "zip two sources" in {
    scoped {
      val c1 = Source.fromValues(1, 2, 3, 0)
      val c2 = Source.fromValues(4, 5, 6)

      val s = c1.zip(c2)

      s.receive() shouldBe (1, 4)
      s.receive() shouldBe (2, 5)
      s.receive() shouldBe (3, 6)
      s.receive() shouldBe ChannelClauseResult.Done
    }
  }

  it should "merge two sources" in {
    scoped {
      val c1 = Source.fromValues(1, 2, 3)
      val c2 = Source.fromValues(4, 5, 6)

      val s = c1.merge(c2)

      s.toList.sorted shouldBe List(1, 2, 3, 4, 5, 6)
    }
  }

  it should "pipe one source to another" in {
    scoped {
      val c1 = Source.fromValues(1, 2, 3)
      val c2 = Channel[Int]()

      fork { c1.pipeTo(c2) }

      c2.toList shouldBe List(1, 2, 3)
    }
  }

  it should "create a source from a fork" in {
    scoped {
      val f = fork(1)
      val c = Source.fromFork(f)
      c.toList shouldBe List(1)
    }
  }

  it should "create an iterating source" in {
    scoped {
      val c = Source.iterate(0)(1)(_ + 1)
      c.take(3).toList shouldBe List(1, 2, 3)
    }
  }

  it should "unfold a function" in {
    scoped {
      val c = Source.unfold(0)(0)(i => if i < 3 then Some((i, i + 1)) else None)
      c.toList shouldBe List(0, 1, 2)
    }
  }

  it should "concatenate sources" in {
    scoped {
      val s1 = Source.fromValues("a", "b", "c")
      val s2 = Source.fromValues("d", "e", "f")
      val s3 = Source.fromValues("g", "h", "i")

      val s = Source.concat(List(() => s1, () => s2, () => s3))

      s.toList shouldBe List("a", "b", "c", "d", "e", "f", "g", "h", "i")
    }
  }
}
