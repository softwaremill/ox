package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsForeachTest extends AnyFlatSpec with Matchers {

  behavior of "Source.foreach"

  it should "iterate over a source" in {
    val c = Channel.buffered[Int](10)
    c.sendSafe(1)
    c.sendSafe(2)
    c.sendSafe(3)
    c.doneSafe()

    var r: List[Int] = Nil
    c.foreach(v => r = v :: r)

    r shouldBe List(3, 2, 1)
  }

  it should "iterate over a source using for-syntax" in {
    val c = Channel.buffered[Int](10)
    c.sendSafe(1)
    c.sendSafe(2)
    c.sendSafe(3)
    c.doneSafe()

    var r: List[Int] = Nil
    for {
      v <- c
    } r = v :: r

    r shouldBe List(3, 2, 1)
  }

  it should "convert source to a list" in {
    val c = Channel.buffered[Int](10)
    c.sendSafe(1)
    c.sendSafe(2)
    c.sendSafe(3)
    c.doneSafe()

    c.toList shouldBe List(1, 2, 3)
  }
}
