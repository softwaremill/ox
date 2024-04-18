package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosedUnion.map

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class ChannelTest extends AnyFlatSpec with Matchers with Eventually {
  List(0, 1, 2, 100, 10000).foreach { capacity =>
    s"channel with capacity $capacity" should "send and receive two spaced elements" in {
      val c = Channel.withCapacity[Int](capacity)
      unsupervised {
        val f1 = forkUnsupervised {
          c.receive()
        }
        val f2 = forkUnsupervised {
          c.receive()
        }

        sleep(100.millis)
        c.send(1)
        sleep(100.millis)
        c.send(2)

        val r1 = f1.join()
        val r2 = f2.join()

        r1 + r2 shouldBe 3
      }
    }

    it should "send and receive many elements, with concurrent senders & receivers" in {
      val n = 10000
      val c = Channel.withCapacity[Int](capacity)
      unsupervised {
        val fs = (1 to 2 * n).map { i =>
          if i % 2 == 0 then
            forkUnsupervised {
              c.send(i / 2); 0
            }
          else
            forkUnsupervised {
              c.receive()
            }
        }

        fs.map(_.join()).sum shouldBe n * (n + 1) / 2
      }
    }

    it should "select from two receives, if the last one has elements" in supervised {
      val c1 = Channel.withCapacity[String](capacity)
      val c2 = Source.fromIterable(List("a"))

      select(c1, c2) shouldBe "a"
    }

    it should "select from three receives, if the last one has elements" in supervised {
      val c1 = Channel.withCapacity[String](capacity)
      val c2 = Channel.withCapacity[String](capacity)
      val c3 = Source.fromIterable(List("a"))

      select(c1, c2, c3) shouldBe "a"
    }

    it should "select a receive from multiple channels" in {
      val n = 100
      val cn = 10

      val cs = (1 to cn).map(_ => Channel.withCapacity[Int](capacity)).toList
      unsupervised {
        cs.foreach { c =>
          (1 to n).foreach { i =>
            forkUnsupervised(c.send(i))
          }
        }

        val result = new AtomicInteger(0)

        forkUnsupervised {
          forever {
            result.addAndGet(select(cs.map(_.receiveClause)).value).discard
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

      val cs = (1 to cn).map(_ => Channel.withCapacity[Int](capacity)).toList
      unsupervised {
        cs.foreach { c =>
          forkUnsupervised {
            (1 to n).foreach(c.send)
            sleep(10.millis)
            c.done()
          }
        }

        val result = new ConcurrentLinkedQueue[Int | ChannelClosed]()

        forkUnsupervised {
          var loop = true
          while loop do {
            val r = selectOrClosed(cs.map(_.receiveClause))
            result.add(r.map(_.value))
            loop = r != ChannelClosed.Done
          }
        }

        eventually {
          result.asScala.toList should have size (n * cn + 1) // all numbers + done
        }
      }
    }

    it should "properly report channel state" in {
      // given
      val c1 = Channel.withCapacity[Int](capacity)
      val c2 = Channel.withCapacity[Int](capacity)
      val c3 = Channel.withCapacity[Int](capacity)
      val c4 = Channel.withCapacity[Int](capacity)

      supervised {
        fork {
          c2.send(10)
        }
        sleep(100.millis) // wait for the send to suspend

        // when
        c1.done() // done, and no values pending to receive
        c2.done() // done, and values pending
        c3.error(new RuntimeException())

        // then
        c1.isClosedForReceive shouldBe true
        c2.isClosedForReceive shouldBe false
        c3.isClosedForReceive shouldBe true
        c4.isClosedForReceive shouldBe false

        c1.isClosedForSend shouldBe true
        c2.isClosedForSend shouldBe true
        c3.isClosedForSend shouldBe true
        c4.isClosedForSend shouldBe false

        c1.isClosedForReceiveDetail should matchPattern { case Some(_) => }
        c2.isClosedForReceiveDetail shouldBe None
        c3.isClosedForReceiveDetail should matchPattern { case Some(_) => }
        c4.isClosedForReceiveDetail shouldBe None

        c1.isClosedForSendDetail should matchPattern { case Some(_) => }
        c2.isClosedForSendDetail should matchPattern { case Some(_) => }
        c3.isClosedForSendDetail should matchPattern { case Some(_) => }
        c4.isClosedForSendDetail shouldBe None
      }
    }

    it should "select from a non-done channel, if a value is immediately available" in {
      val c1 = Channel.withCapacity[Int](capacity)
      val c2 = Channel.withCapacity[Int](capacity)
      unsupervised {
        forkUnsupervised {
          c1.send(1)
          c2.done()
        }

        sleep(100.millis) // let the fork progress
        select(c1.receiveClause, c2.receiveClause) shouldBe c1.Received(1)
      }
    }

    it should "select a done channel, when the channel is done immediately" in {
      val c1 = Channel.withCapacity[Int](capacity)
      val c2 = Channel.withCapacity[Int](capacity)
      unsupervised {
        forkUnsupervised {
          c2.done()
        }

        sleep(100.millis) // let the fork progress
        selectOrClosed(c1, c2) shouldBe ChannelClosed.Done
      }
    }

    it should "select a done channel, when the channel becomes done" in {
      val c1 = Channel.withCapacity[Int](capacity)
      val c2 = Channel.withCapacity[Int](capacity)
      unsupervised {
        forkUnsupervised {
          sleep(100.millis) // let the select block
          c2.done()
        }

        selectOrClosed(c1, c2) shouldBe ChannelClosed.Done
      }
    }
  }

  "buffered channel" should "select a send when one is available" in {
    val c1 = Channel.buffered[Int](1)
    val c2 = Channel.buffered[Int](1)
    select(c1.sendClause(1), c2.sendClause(2)) should matchPattern { case _: Channel[_]#Sent => }
    select(c1.sendClause(1), c2.sendClause(2)) should matchPattern { case _: Channel[_]#Sent => }

    Set(c1.receive(), c2.receive()) shouldBe Set(1, 2)
  }

  "channel" should "receive from a channel until done" in {
    val c = Channel.buffered[Int](3)
    c.send(1)
    c.send(2)
    c.done()

    c.receive() shouldBe 1
    c.receive() shouldBe 2
    c.receiveOrClosed() shouldBe ChannelClosed.Done
    c.receiveOrClosed() shouldBe ChannelClosed.Done // repeat
  }

  it should "not receive from a channel in case of an error" in {
    val c = Channel.buffered[Int](3)
    c.send(1)
    c.send(2)
    c.error(new RuntimeException())

    c.receiveOrClosed() should matchPattern { case _: ChannelClosed.Error => }
    c.receiveOrClosed() should matchPattern { case _: ChannelClosed.Error => } // repeat
  }

  "rendezvous channel" should "wait until elements are transmitted" in {
    val c = Channel.rendezvous[String]
    val trail = ConcurrentLinkedQueue[String]()
    unsupervised {
      forkUnsupervised {
        c.send("x")
        trail.add("S")
      }
      forkUnsupervised {
        c.send("y")
        trail.add("S")
      }
      val f3 = forkUnsupervised {
        sleep(100.millis)
        trail.add("R1")
        val r1 = c.receive()
        sleep(100.millis)
        trail.add("R2")
        val r2 = c.receive()
        Set(r1, r2) shouldBe Set("x", "y")
      }

      f3.join()
      sleep(100.millis)

      trail.asScala.toList shouldBe List("R1", "S", "R2", "S")
    }
  }

  it should "select a send when a receive is waiting" in {
    val c1 = Channel.rendezvous[Int]
    val c2 = Channel.rendezvous[Int]

    unsupervised {
      val f1 = forkUnsupervised(c1.receive())
      select(c1.sendClause(1), c2.sendClause(2))
      f1.join() shouldBe 1

      val f2 = forkUnsupervised(c2.receive())
      select(c1.sendClause(1), c2.sendClause(2))
      f2.join() shouldBe 2
    }
  }

  it should "select a send or receive depending on availability" in {
    val c1 = Channel.rendezvous[Int]
    val c2 = Channel.rendezvous[Int]

    unsupervised {
      val f1 = forkUnsupervised(c1.receive())
      select(c1.sendClause(1), c2.receiveClause) shouldBe c1.Sent()
      f1.join() shouldBe 1

      val f2 = forkUnsupervised(c2.send(2))
      select(c1.sendClause(1), c2.receiveClause) shouldBe c2.Received(2)
      f2.join() shouldBe ()
    }
  }

  "default" should "use the default value if the clauses are not satisfiable" in {
    val c1 = Channel.rendezvous[Int]
    select(c1.receiveClause, Default(10)) shouldBe DefaultResult(10)

    val c2 = Channel.rendezvous[Int]
    select(c2.sendClause(5), Default(10)) shouldBe DefaultResult(10)

    // the send should not have succeeded
    select(c2.receiveClause, Default(10)) shouldBe DefaultResult(10)
  }

  it should "not use the default value if a clause is satisfiable" in {
    val c1 = Channel.buffered[Int](1)
    c1.send(5)
    select(c1.receiveClause, Default(10)) shouldBe c1.Received(5)

    val c2 = Channel.buffered[Int](1)
    select(c2.sendClause(5), Default(10)) shouldBe c2.Sent()
  }

  it should "not use the default value if the channel is done" in {
    val c1 = Channel.buffered[Int](1)
    c1.done()
    selectOrClosed(c1.receiveClause, Default(10)) shouldBe ChannelClosed.Done
  }

  it should "use the default value once a source is done (buffered channel, stress test)" in {
    for (i <- 1 to 100) {
      info(s"iteration $i")

      unsupervised {
        // given
        val c = Channel.buffered[Int](3)
        c.send(1)
        c.send(2)
        c.send(3)

        // when
        val result = for (_ <- 1 to 4) yield select(c.receiveClause, Default(5)).value

        // then
        result.toList shouldBe List(1, 2, 3, 5)
      }
    }
  }
}
