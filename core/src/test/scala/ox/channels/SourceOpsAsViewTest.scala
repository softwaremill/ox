package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Try}

class SourceOpsAsViewTest extends AnyFlatSpec with Matchers with Eventually {
  it should "map over a source as a view" in {
    val c: Channel[Int] = Channel()

    supervised {
      fork {
        c.send(10)
        c.send(20)
        c.send(30)
        c.done()
      }

      val s2 = c.mapAsView(_ + 1)
      s2.receive() shouldBe 11
      s2.receive() shouldBe 21
      s2.receive() shouldBe 31
      s2.receive() shouldBe ChannelClosed.Done
    }
  }

  it should "return done, if a channels is done immediately" in {
    val c1: Channel[Int] = Channel()
    val c2: Channel[Int] = Channel()

    c1.done()

    val s1 = c1.mapAsView(_ + 1)
    val s2 = c2.mapAsView(_ + 1)

    select(s1, s2) shouldBe ChannelClosed.Done
  }

  it should "select from sources mapped as view" in {
    val c1: Channel[Int] = Channel()
    val c2: Channel[Int] = Channel()

    supervised {
      fork {
        c1.send(10)
        c1.send(20)
        c1.send(30)
      }

      fork {
        c2.send(100)
        c2.send(200)
        c2.send(300)
      }

      val s1 = c1.mapAsView(_ + 1)
      val s2 = c2.mapAsView(_ + 1)

      (for (_ <- 1 to 6) yield select(s1.receiveClause, s2.receiveClause).map(_.value)).toSet shouldBe Set(
        101, 201, 301, 11, 21, 31
      )
    }
  }

  it should "filter over a source as a view" in {
    val c: Channel[Int] = Channel()

    supervised {
      fork {
        c.send(1)
        c.send(2)
        c.send(3)
        c.send(4)
        c.done()
      }

      val s2 = c.filterAsView(_ % 2 == 0)
      s2.receive() shouldBe 2
      s2.receive() shouldBe 4
      s2.receive() shouldBe ChannelClosed.Done
    }
  }

  it should "select from sources filtered as a view" in {
    val c1: Channel[Int] = Channel()
    val c2: Channel[Int] = Channel()

    supervised {
      fork {
        c1.send(1)
        c1.send(2)
        c1.send(3)
        c1.send(4)
      }

      fork {
        c2.send(11)
        c2.send(12)
        c2.send(13)
        c2.send(14)
      }

      val s1 = c1.filterAsView(_ % 2 == 0)
      val s2 = c2.filterAsView(_ % 2 == 0)

      (for (_ <- 1 to 4) yield select(s1.receiveClause, s2.receiveClause).map(_.value)).toSet shouldBe Set(2, 4, 12, 14)
    }
  }

  it should "propagate exceptions to the calling select" in {
    val c: Channel[Int] = Channel()

    supervised {
      fork {
        c.send(1)
        c.send(2)
        c.send(3)
        c.send(4)
        c.done()
      }

      val c1 = Channel();
      val s2 = c.filterAsView(v => if v % 2 == 0 then true else throw new RuntimeException("test"))

      Try(select(c1.receiveClause, s2.receiveClause)) should matchPattern { case Failure(e) if e.getMessage == "test" => }
      select(c1.receiveClause, s2.receiveClause).map(_.value) shouldBe 2
      Try(select(c1.receiveClause, s2.receiveClause)) should matchPattern { case Failure(e) if e.getMessage == "test" => }
      select(c1.receiveClause, s2.receiveClause).map(_.value) shouldBe 4
      select(c1.receiveClause, s2.receiveClause) shouldBe ChannelClosed.Done
    }
  }
}
