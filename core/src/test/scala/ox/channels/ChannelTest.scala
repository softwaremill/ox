package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class ChannelTest extends AnyFlatSpec with Matchers with Eventually {
  List(0, 1, 2).foreach { capacity =>
    s"channel with capacity $capacity" should "send and receive two spaced elements" in {
      val c = Channel[Int](capacity)
      scoped {
        val f1 = fork {
          c.receive().orThrow
        }
        val f2 = fork {
          c.receive().orThrow
        }

        Thread.sleep(100L)
        c.send(1).orThrow
        Thread.sleep(100L)
        c.send(2).orThrow

        val r1 = f1.join()
        val r2 = f2.join()

        r1 + r2 shouldBe 3
      }
    }

    it should "send and receive many elements, with concurrent senders & receivers" in {
      val n = 10000
      val c = Channel[Int](capacity)
      scoped {
        val fs = (1 to 2 * n).map { i =>
          if i % 2 == 0 then
            fork {
              c.send(i / 2).orThrow; 0
            }
          else
            fork {
              c.receive().orThrow
            }
        }

        fs.map(_.join()).sum shouldBe n * (n + 1) / 2
      }
    }

    it should "select a receive from multiple channels" in {
      val n = 100
      val cn = 10

      val cs = (1 to cn).map(_ => Channel[Int](capacity)).toList
      scoped {
        cs.foreach { c =>
          (1 to n).foreach { i =>
            fork(c.send(i).orThrow)
          }
        }

        val result = new AtomicInteger(0)

        fork {
          forever {
            result.addAndGet(select(cs.map(_.receiveClause)).orThrow.value)
          }
        }

        eventually {
          result.get() shouldBe cn * n * (n + 1) / 2
        }
      }
    }

    it should "select a receive until all channels are done" in {
      val n = 10
      val cn = 10

      val cs = (1 to cn).map(_ => Channel[Int](capacity)).toList
      scoped {
        cs.foreach { c =>
          fork {
            (1 to n).foreach(c.send)
            Thread.sleep(10)
            c.done()
          }
        }

        val result = new ConcurrentLinkedQueue[Int | ChannelClosed]()

        fork {
          var loop = true
          while loop do {
            val r = select(cs.map(_.receiveClause))
            result.add(r.map(_.value))
            loop = r != ChannelClosed.Done
          }
        }

        eventually {
          result.asScala.toList should have size (n * cn + 1) // all numbers + done
        }
      }
    }
  }

  "buffered channel" should "select a send when one is available" in {
    val c1 = Channel[Int](1)
    val c2 = Channel[Int](1)
    select(c1.sendClause(1), c2.sendClause(2)).orThrow should matchPattern { case _: Channel[_]#Sent => }
    select(c1.sendClause(1), c2.sendClause(2)).orThrow should matchPattern { case _: Channel[_]#Sent => }

    Set(c1.receive().orThrow, c2.receive().orThrow) shouldBe Set(1, 2)
  }

  "channel" should "receive from a channel until done" in {
    val c = Channel[Int](3)
    c.send(1)
    c.send(2)
    c.done()

    c.receive().orThrow shouldBe 1
    c.receive().orThrow shouldBe 2
    c.receive() shouldBe ChannelClosed.Done
    c.receive() shouldBe ChannelClosed.Done // repeat
  }

  it should "not receive from a channel in case of an error" in {
    val c = Channel[Int](3)
    c.send(1)
    c.send(2)
    c.error()

    c.receive() shouldBe ChannelClosed.Error(None)
    c.receive() shouldBe ChannelClosed.Error(None) // repeat
  }

  it should "select a receive from a channel if one is not done" in {
    val c1 = Channel[Int]()
    c1.done()

    val c2 = Channel[Int](1)
    c2.send(1)

    select(c1.receiveClause, c2.receiveClause).map(_.value) shouldBe 1
  }

  "direct channel" should "wait until elements are transmitted" in {
    val c = Channel[String](0)
    val trail = ConcurrentLinkedQueue[String]()
    scoped {
      fork {
        c.send("x").orThrow
        trail.add("S")
      }
      fork {
        c.send("y").orThrow
        trail.add("S")
      }
      val f3 = fork {
        Thread.sleep(100L)
        trail.add("R1")
        val r1 = c.receive().orThrow
        Thread.sleep(100L)
        trail.add("R2")
        val r2 = c.receive().orThrow
        Set(r1, r2) shouldBe Set("x", "y")
      }

      f3.join()
      Thread.sleep(100L)

      trail.asScala.toList shouldBe List("R1", "S", "R2", "S")
    }
  }

  it should "select a send when a receive is waiting" in {
    val c1 = Channel[Int](0)
    val c2 = Channel[Int](0)

    scoped {
      val f1 = fork(c1.receive().orThrow)
      select(c1.sendClause(1), c2.sendClause(2)).orThrow
      f1.join() shouldBe 1

      val f2 = fork(c2.receive().orThrow)
      select(c1.sendClause(1), c2.sendClause(2)).orThrow
      f2.join() shouldBe 2
    }
  }

  it should "select a send or receive depending on availability" in {
    val c1 = Channel[Int](0)
    val c2 = Channel[Int](0)

    scoped {
      val f1 = fork(c1.receive().orThrow)
      select(c1.sendClause(1), c2.receiveClause).orThrow shouldBe c1.Sent()
      f1.join() shouldBe 1

      val f2 = fork(c2.send(2).orThrow)
      select(c1.sendClause(1), c2.receiveClause).orThrow shouldBe c2.Received(2)
      f2.join() shouldBe ()
    }
  }

  "default" should "use the default value if the clauses are not satisfiable" in {
    val c1 = Channel[Int](0)
    select(c1.receiveClause, Default(10)) shouldBe DefaultResult(10)

    val c2 = Channel[Int](0)
    select(c2.sendClause(5), Default(10)) shouldBe DefaultResult(10)

    // the send should not have succeeded
    select(c2.receiveClause, Default(10)) shouldBe DefaultResult(10)
  }

  it should "not use the default value if a clause is satisfiable" in {
    val c1 = Channel[Int](1)
    c1.send(5)
    select(c1.receiveClause, Default(10)) shouldBe c1.Received(5)

    val c2 = Channel[Int](1)
    select(c2.sendClause(5), Default(10)) shouldBe c2.Sent()
  }
}
