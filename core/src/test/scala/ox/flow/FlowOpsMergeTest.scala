package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import scala.concurrent.duration.DurationInt
import ox.channels.ChannelClosedException

class FlowOpsMergeTest extends AnyFlatSpec with Matchers:
  behavior of "merge"

  it should "merge two simple flows" in:
    val c1 = Flow.fromValues(1, 2, 3)
    val c2 = Flow.fromValues(4, 5, 6)

    val s = c1.merge(c2)

    s.runToList().sorted shouldBe List(1, 2, 3, 4, 5, 6)

  it should "merge two async flows" in:
    val c1 = Flow.fromValues(1, 2, 3).buffer()
    val c2 = Flow.fromValues(4, 5, 6).buffer()

    val s = c1.merge(c2)

    s.runToList().sorted shouldBe List(1, 2, 3, 4, 5, 6)

  it should "merge with a tick flow" in:
    val c1 = Flow.tick(200.millis, 0).take(2)
    val c2 = Flow.tick(75.millis, 1).take(3)

    val r = c1.merge(c2).runToList() // [0/1] - any order, then: 1, 1, 0, 1
    r should contain inOrder (1, 0)
    r should contain inOrder (0, 1)

  it should "propagate error from the left" in:
    val c1 = Flow.fromValues(1, 2, 3).concat(Flow.failed(new IllegalStateException))
    val c2 = Flow.fromValues(4, 5, 6)

    val s = c1.merge(c2)

    intercept[ChannelClosedException.Error] {
      s.runToList()
    }.getCause() shouldBe a[IllegalStateException]

  it should "propagate error from the right" in:
    val c1 = Flow.fromValues(1, 2, 3)
    val c2 = Flow.fromValues(4, 5, 6).concat(Flow.failed(new IllegalStateException))

    val s = c1.merge(c2)

    intercept[ChannelClosedException.Error] {
      s.runToList()
    }.getCause() shouldBe a[IllegalStateException]

  it should "merge two flows, emitting all elements from the left when right completes" in:
    val c1 = Flow.fromValues(1, 2, 3, 4).throttle(1, 100.millis)
    val c2 = Flow.fromValues(5, 6).throttle(1, 100.millis)

    val s = c1.merge(c2)

    s.runToList().sorted shouldBe List(1, 2, 3, 4, 5, 6)

  it should "merge two flows, emitting all elements from the right when left completes" in:
    val c1 = Flow.fromValues(1, 2).throttle(1, 100.millis)
    val c2 = Flow.fromValues(3, 4, 5, 6).throttle(1, 100.millis)

    val s = c1.merge(c2)

    s.runToList().sorted shouldBe List(1, 2, 3, 4, 5, 6)

  it should "merge two flows, completing the resulting flow when the left flow completes" in:
    val c1 = Flow.fromValues(1, 2).throttle(1, 100.millis)
    val c2 = Flow.fromValues(3, 4, 5, 6).throttle(1, 100.millis)

    val s = c1.merge(c2, propagateDoneLeft = true)

    s.runToList().sorted should (be(List(1, 2, 3, 4)) or be(List(1, 2, 3)))

  it should "merge two flows, completing the resulting flow when the right flow completes" in:
    val c1 = Flow.fromValues(1, 2, 3, 4).throttle(1, 100.millis)
    val c2 = Flow.fromValues(5, 6).throttle(1, 100.millis)

    val s = c1.merge(c2, propagateDoneRight = true)

    s.runToList().sorted should (be(List(1, 2, 5, 6)) or be(List(1, 2, 5)))
end FlowOpsMergeTest
