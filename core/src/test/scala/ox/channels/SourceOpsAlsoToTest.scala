package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.util.{Failure, Try}

class SourceOpsAlsoToTest extends AnyFlatSpec with Matchers {

  behavior of "Source.alsoTo"

  it should "send to both sinks" in supervised {
    val c = Channel.withCapacity[Int](10)
    Source.fromValues(1, 2, 3).alsoTo(c).toList shouldBe List(1, 2, 3)
    c.toList shouldBe List(1, 2, 3)
  }

  it should "send to both sinks and not hang when other sink is rendezvous channel" in supervised {
    val c = Channel.rendezvous[Int]
    val f = fork {
      c.toList
    }
    Source.fromValues(1, 2, 3, 4, 5).alsoTo(c).toList shouldBe List(1, 2, 3, 4, 5)
    f.join() shouldBe List(1, 2, 3, 4, 5)
  }

  it should "close main channel when other closes" in supervised {
    val c = Channel.withCapacity[Int](1) // TODO: check why the test hangs with rendezvous channel
    val f = fork {
      val list = List(c.receiveOrClosed(), c.receiveOrClosed(), c.receiveOrClosed())
      c.doneOrClosed()
      list
    }
    // we would expect exactly 4 elements, but there can be more values because
    // the channel's buffer is resized internally when it closes, see `com.softwaremill.jox.Channel.closeOrClosed`
    Source.fromIterable(1 to 100).alsoTo(c).toList.size should be < 25
    f.join() shouldBe List(1, 2, 3)
  }

  it should "close main channel with error when other errors" in supervised {
    val c = Channel.withCapacity[Int](1)
    val f = fork {
      c.receiveOrClosed()
      c.receiveOrClosed()
      c.receiveOrClosed()
      c.errorOrClosed(new RuntimeException("stop!"))
    }

    Try(Source.fromIterable(1 to 100).alsoTo(c).toList) shouldBe a[Failure[RuntimeException]]
    f.join()
  }

  it should "close other channel when main closes" in supervised {
    val other = Channel.rendezvous[Int]
    val forkOther = fork {
      other.toList
    }
    val main = Source.fromIterable(1 to 100).alsoTo(other).asInstanceOf[Channel[Int]]

    List(main.receiveOrClosed(), main.receiveOrClosed(), main.receiveOrClosed()) shouldBe List(1, 2, 3)

    main.doneOrClosed()
    // we would expect exactly 3 elements, but there can be more values because
    // the channel's buffer is resized internally when it closes, see `com.softwaremill.jox.Channel.closeOrClosed`
    forkOther.join().size should be < 25
  }

  it should "close other channel with error when main errors" in supervised {
    val other = Channel.rendezvous[Int]
    val forkOther = fork {
      Try(other.toList)
    }
    val main = Source.fromIterable(1 to 100).alsoTo(other).asInstanceOf[Channel[Int]]

    main.receiveOrClosed()
    main.receiveOrClosed()
    main.receiveOrClosed()
    main.errorOrClosed(new RuntimeException("stop!"))

    forkOther.join() shouldBe a[Failure[RuntimeException]]
  }
}
