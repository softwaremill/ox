package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.util.{Failure, Try}
import ox.channels.Channel
import ox.channels.ChannelClosedException

class FlowOpsAlsoToTest extends AnyFlatSpec with Matchers:

  behavior of "alsoTo"

  it should "send to both sinks" in:
    val c = Channel.withCapacity[Int](10)
    Flow.fromValues(1, 2, 3).alsoTo(c).runToList() shouldBe List(1, 2, 3)
    c.toList shouldBe List(1, 2, 3)

  it should "send to both sinks and not hang when other sink is rendezvous channel" in supervised:
    val c = Channel.rendezvous[Int]
    val f = fork(c.toList)
    Flow.fromValues(1, 2, 3, 4, 5).alsoTo(c).runToList() shouldBe List(1, 2, 3, 4, 5)
    f.join() shouldBe List(1, 2, 3, 4, 5)

  it should "close main flow when other closes" in supervised:
    // this needs to be unlimited (or at least with a buffer larger than the maximum number of elements sent to c), as
    // otherwise the main thread can get suspended on a .send(), and never get unblocked, and here we complete the
    // channel with a .done
    val c = Channel.unlimited[Int]
    forkDiscard:
      c.receiveOrClosed().discard
      c.receiveOrClosed().discard
      c.receiveOrClosed().discard
      c.doneOrClosed().discard

    a[ChannelClosedException.Done] shouldBe thrownBy(Flow.fromIterable(1 to 100).alsoTo(c).runToList())

  it should "close main flow with error when other errors" in supervised:
    val c = Channel.withCapacity[Int](1)
    val f = fork:
      c.receiveOrClosed().discard
      c.receiveOrClosed().discard
      c.receiveOrClosed().discard
      c.errorOrClosed(new IllegalStateException)

    Try(Flow.fromIterable(1 to 100).alsoTo(c).runToList()) shouldBe a[Failure[IllegalStateException]]
    f.join()

  it should "close other channel with error when main errors" in supervised:
    val other = Channel.rendezvous[Int]
    val forkOther = fork(Try(other.toList))
    a[RuntimeException] shouldBe thrownBy(
      Flow.fromIterable(1 to 100).concat(Flow.failed(new IllegalStateException)).alsoTo(other).runToList()
    )

    forkOther.join() shouldBe a[Failure[IllegalStateException]]
end FlowOpsAlsoToTest
