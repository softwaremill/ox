package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import scala.concurrent.duration.*
import ox.channels.Channel
import ox.channels.ChannelClosed

class FlowOpsAlsoToTapTest extends AnyFlatSpec with Matchers:

  behavior of "alsoToTap"

  it should "send to both sinks when other is faster" in:
    val other = Channel.withCapacity[Int](10)
    Flow
      .fromValues(1, 2, 3)
      .alsoToTap(other)
      .map(v =>
        sleep(50.millis); v
      )
      .runToList() shouldBe List(1, 2, 3)
    other.toList shouldBe List(1, 2, 3)

  it should "send to both sinks when other is slower" in supervised:
    val other = Channel.rendezvous[Int]
    val slowConsumerFork = fork:
      var vec = Vector.empty[Int]
      repeatWhile:
        sleep(100.millis)
        other.receiveOrClosed() match
          case ChannelClosed.Done     => false
          case ChannelClosed.Error(_) => false
          case t: Int                 => vec = vec :+ t; true
      vec
    val main = Channel.rendezvous[Int]
    forkDiscard:
      for i <- 1 to 20 do
        main.send(i)
        sleep(10.millis)
      main.done()
    Flow.fromSource(main).alsoToTap(other).runToList() shouldBe (1 to 20).toList
    val otherElements = slowConsumerFork.join()
    otherElements.size should be < 10

  it should "not fail the flow when the other sink fails" in supervised:
    val other = Channel.rendezvous[Int]
    val f = fork:
      val v = other.receiveOrClosed()
      other.error(new RuntimeException("boom!"))
      v
    Flow
      .fromIterable(1 to 10)
      .map(v =>
        sleep(10.millis); v
      )
      .alsoToTap(other)
      .runToList() shouldBe (1 to 10).toList
    f.join() shouldBe 1

  it should "not close the flow when the other sink closes" in supervised:
    val other = Channel.rendezvous[Int]
    val f = fork:
      val v = other.receiveOrClosed()
      other.done()
      v
    Flow
      .fromIterable(1 to 10)
      .map(v =>
        sleep(10.millis); v
      )
      .alsoToTap(other)
      .runToList() shouldBe (1 to 10).toList
    f.join() shouldBe 1
end FlowOpsAlsoToTapTest
