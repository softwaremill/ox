package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.Ox.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
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

      val s = c.map(_ * 2)

      s.receive() shouldBe Right(2)
      s.receive() shouldBe Right(4)
      s.receive() shouldBe Right(6)
      s.receive() shouldBe Left(ChannelState.Done)
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

      s.receive() shouldBe Right(2)
      s.receive() shouldBe Right(4)
      s.receive() shouldBe Right(6)
      s.receive() shouldBe Left(ChannelState.Done)
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
}
