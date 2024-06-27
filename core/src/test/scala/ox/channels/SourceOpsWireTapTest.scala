package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import scala.concurrent.duration.*

class SourceOpsWireTapTest extends AnyFlatSpec with Matchers {

  behavior of "Source.wireTap"

  it should "send to both sinks when other is fast" in supervised {
    val other = Channel.withCapacity[Int](10)
    Source.fromValues(1, 2, 3).wireTap(other).toList shouldBe List(1, 2, 3)
    other.toList shouldBe List(1, 2, 3)
  }

  it should "send to both sinks when other is slow" in supervised {
    val other = Channel.rendezvous[Int]
    val slowConsumerFork = fork {
      var vec = Vector.empty[Int]
      repeatWhile {
        sleep(100.millis)
        other.receiveOrClosed() match
          case ChannelClosed.Done     => false
          case ChannelClosed.Error(_) => false
          case t: Int                 => vec = vec :+ t; true
      }
      vec
    }
    val main = Channel.rendezvous[Int]
    fork {
      for (i <- 1 to 20) {
        main.send(i)
        sleep(10.millis)
      }
      main.done()
    }
    main.wireTap(other).toList shouldBe (1 to 20).toList
    val otherElements = slowConsumerFork.join()
    otherElements.size should (be > 3 and be < 10)
    otherElements.take(3) shouldBe Vector(1, 2, 3)
  }
}
