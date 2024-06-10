package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsTakeWhileTest extends AnyFlatSpec with Matchers {
  behavior of "Source.takeWhile"

  it should "not take from the empty source" in supervised {
    val s = Source.empty[Int]
    s.takeWhile(_ < 3).toList shouldBe List.empty
  }

  it should "take as long as predicate is satisfied" in supervised {
    val s = Source.fromValues(1, 2, 3)
    s.takeWhile(_ < 3).toList shouldBe List(1, 2)
  }

  it should "close after returning the last element" in supervised {
    val c = Channel.buffered[Int](4)
    for i <- 1 to 4 do c.send(i)
    val s = c.takeWhile(_ < 3)
    s.receive() shouldBe 1
    s.receive() shouldBe 2
    s.receiveOrClosed() shouldBe ChannelClosed.Done
  }

  it should "take the failed element if includeFirstFailing = true" in supervised {
    val c = Channel.buffered[Int](4)
    for i <- 1 to 4 do c.send(i)
    val s = c.takeWhile(_ < 3, includeFirstFailing = true)
    s.receive() shouldBe 1
    s.receive() shouldBe 2
    s.receive() shouldBe 3
    s.receiveOrClosed() shouldBe ChannelClosed.Done
  }

  it should "work if all elements match the predicate" in supervised {
    val s = Source.fromValues(1, 2, 3)
    val s2 = s.takeWhile(_ < 5)
    s2.receive() shouldBe 1
    s2.receive() shouldBe 2
    s2.receive() shouldBe 3
    s2.receiveOrClosed() shouldBe ChannelClosed.Done
  }

  it should "fail the sourcewith the same exception as the initial source" in supervised {
    val c = Channel.buffered[Int](1)
    c.send(1)
    val s = c.takeWhile(_ < 3)
    s.receive() shouldBe 1
    val testException = new Exception("expected error")
    c.error(testException)
    s.receiveOrClosed() shouldBe ChannelClosed.Error(testException)
  }

  it should "fail the source with the same exception as predicate" in supervised {
    val c = Channel.buffered[Int](1)
    val testException = new Exception("expected predicate error")
    fork {
      c.send(1)
      c.send(2)
    }
    val s = c.takeWhile {
      case 1 => true
      case _ => throw testException
    }
    s.receive() shouldBe 1
    s.receiveOrClosed() shouldBe ChannelClosed.Error(testException)
  }

  it should "not take if predicate fails for first or more elements" in supervised {
    val s = Source.fromValues(3, 2, 1)
    s.takeWhile(_ < 3).toList shouldBe List()
  }
}
