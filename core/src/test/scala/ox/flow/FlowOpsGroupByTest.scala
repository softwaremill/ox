package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosedException

import scala.concurrent.duration.DurationInt

class FlowOpsGroupByTest extends AnyFlatSpec with Matchers:
  behavior of "groupBy"

  it should "handle empty flow" in:
    val result = Flow
      .empty[Int]
      .groupBy(10, _ % 10)(v => f => f)
      .runToList()

    result shouldBe empty

  it should "handle single-element flow" in:
    Flow.fromValues(42).groupBy(10, _ % 10)(v => f => f).runToList() shouldBe List(42)

  it should "create simple groups without reaching parallelism limit" in:
    case class Group(v: Int, values: List[Int])

    val result = Flow
      .fromValues(10, 11, 12, 13, 20, 23, 33, 30)
      .groupBy(10, _ % 10)(v => _.mapStatefulConcat(Group(v, Nil))((g, i) => (g.copy(values = i :: g.values), Nil), g => Some(g)))
      .runToList()
      .toSet

    result shouldBe Set(Group(0, List(30, 20, 10)), Group(1, List(11)), Group(2, List(12)), Group(3, List(33, 23, 13)))

  it should "complete groups when the parallelism limit is reached" in:
    case class Group(v: Int, values: List[Int])

    val result = Flow
      .fromValues(10, 11, 12, 13, 20, 23, 33, 30)
      // only one group should be active at any time
      .groupBy(1, _ % 10)(v => _.mapStatefulConcat(Group(v, Nil))((g, i) => (g.copy(values = i :: g.values), Nil), g => Some(g)))
      .runToList()

    result shouldBe List(
      Group(0, List(10)),
      Group(1, List(11)),
      Group(2, List(12)),
      Group(3, List(13)),
      // re-created group
      Group(0, List(20)),
      // two elements from the same group in a row
      Group(3, List(33, 23)),
      Group(0, List(30))
    )

  it should "not exceed the parallelism limit, completing earliest-active child flows as done when necessary" in:
    case class Group(v: Int, values: List[Int])

    val result = Flow
      .fromValues(10, 11, 12, 22, 21, 20, 32, 13 /* exceeds limit, group 1 should complete */, 42, 30, 23,
        31 /* group 2 completed, group 1 re-created */ )
      .groupBy(3, _ % 10)(v => _.mapStatefulConcat(Group(v, Nil))((g, i) => (g.copy(values = i :: g.values), Nil), g => Some(g)))
      .runToList()
      .toSet // the order of emitting the groups might be partially random (due to concurrency)

    result shouldBe Set(
      Group(0, List(30, 20, 10)),
      Group(1, List(21, 11)),
      Group(1, List(31)),
      Group(2, List(42, 32, 22, 12)),
      Group(3, List(23, 13))
    )

  it should "handle large flows" in:
    case class Group(v: Int, values: List[Int])

    val input = 1 to 100000
    val result = Flow
      .fromIterable(input)
      .groupBy(100, _ % 100)(v => _.mapStatefulConcat(Group(v, Nil))((g, i) => (g.copy(values = i :: g.values), Nil), g => Some(g)))
      .runToList()

    result.size shouldBe 100
    result.map(_.values.sum).sum shouldBe input.sum

  it should "handle non-integer grouping keys" in:
    case class Group(v: String, values: List[Int])

    val result = Flow
      .fromValues(10, 11, 12, 13, 20, 23, 33, 30)
      .groupBy(10, v => if v % 2 == 0 then "even" else "odd")(v =>
        _.mapStatefulConcat(Group(v, Nil))((g, i) => (g.copy(values = i :: g.values), Nil), g => Some(g))
      )
      .runToList()
      .toSet

    result shouldBe Set(Group("even", List(30, 20, 12, 10)), Group("odd", List(33, 23, 13, 11)))

  it should "group when child processing is slow" in:
    val result = Flow
      .fromValues((1 to 30)*)
      // the number of elements exceeds the buffer, the parent will get blocked
      .groupBy(1, _ => 0)(v => _.tap(_ => sleep(10.millis)))
      .runToList()

    result shouldBe (1 to 30).toList

  it should "propagate errors from child flows" in:
    the[ChannelClosedException]
      .thrownBy({
        Flow
          .fromValues(10, 11, 12, 13, 20, 23, 33, 30)
          .groupBy(10, _ % 10)(v => f => f.tap(i => if i == 13 then throw new RuntimeException("boom!")))
          .runToList()
      })
      .getCause() should have message "boom!"

  it should "propagate errors from child flows when the parent is blocked on sending" in:
    the[ChannelClosedException]
      .thrownBy({
        Flow
          .fromValues((1 to 100)*)
          // all values go to one group; the sleep ensures that the parent will get blocked on sending to the child channel,
          // before the exception is thrown
          .groupBy(1, _ => 0)(v => f => f.tap(_ => sleep(100.millis).tap(_ => throw new RuntimeException("boom!"))))
          .runToList()
      })
      .getCause() should have message "boom!"

  it should "RuntimeException errors from parent flows" in:
    the[ChannelClosedException]
      .thrownBy({
        (Flow
          .fromValues(10, 11, 12, 13, 20, 23, 33, 30)
          .concat(Flow.failed(new RuntimeException("boom!"))))
          .groupBy(10, _ % 10)(v => f => f)
          .runToList()
      })
      .getCause() should have message "boom!"

  it should "throw an IllegalStateException when a child stream is completed by user-provided transformation" in:
    the[Exception].thrownBy {
      Flow
        .fromValues(10, 20, 30)
        // delaying so that the parent doesn't complete too fast, and that we don't get a ChannelClosedException.Done
        .tap(_ => sleep(100.millis))
        .groupBy(10, _ % 10)(v => f => f.take(1))
        .runToList()
    } shouldBe a[IllegalStateException]
end FlowOpsGroupByTest
