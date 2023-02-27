package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.Ox.*

import java.util.concurrent.atomic.AtomicInteger

class ChannelTest extends AnyFlatSpec with Matchers with Eventually {
  it should "send and receive two spaced elements" in {
    val c = Channel[Int]()
    scoped {
      val f1 = fork { c.receive() }
      val f2 = fork { c.receive() }

      Thread.sleep(100L)
      c.send(1)
      Thread.sleep(100L)
      c.send(2)

      val r1 = f1.join()
      val r2 = f2.join()

      r1 + r2 shouldBe 3
    }
  }

  it should "send and receive many elements, with concurrent senders & receivers" in {
    val n = 10000
    val c = Channel[Int]()
    scoped {
      val fs = (1 to 2 * n).map { i =>
        if i % 2 == 0 then fork { c.send(i / 2); 0 }
        else fork { c.receive() }
      }

      fs.map(_.join()).sum shouldBe n * (n + 1) / 2
    }
  }

  it should "select from multiple channels" in {
    val n = 1000
    val cn = 10

    val cs = (1 to cn).map(_ => Channel[Int]()).toList
    scoped {
      cs.foreach { c =>
        (1 to n).foreach { i =>
          fork(c.send(i))
        }
      }

      val result = new AtomicInteger(0)

      fork {
        forever {
          result.addAndGet(select(cs))
        }
      }

      eventually {
        result.get() shouldBe cn * n * (n + 1) / 2
      }
    }
  }
}
