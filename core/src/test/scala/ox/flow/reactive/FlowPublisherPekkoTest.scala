package ox.flow.reactive

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.reactivestreams.FlowAdapters
import org.reactivestreams.Publisher
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.flow.Flow
import ox.useInScope

import scala.concurrent.duration.DurationInt

class FlowPublisherPekkoTest extends AnyFunSuite with Matchers:
  test("a simple flow should emit elements to be processed by a pekko stream"):
    val flow = Flow.fromIterable(1 to 10).map(_ * 2).filter(_ % 3 == 0)

    supervised:
      given ActorSystem = useInScope(ActorSystem("test"))(_.terminate().get().discard)
      Source
        .fromPublisher(FlowAdapters.toPublisher(flow.toPublisher))
        .map(_ * 2)
        .runWith(Sink.collection[Int, List[Int]])
        .get() shouldEqual List(12, 24, 36)

  test("a concurrent flow should emit elements to be processed by a pekko stream"):
    val flow = Flow.tick(100.millis, "x").merge(Flow.tick(200.millis, "y")).take(5)

    supervised:
      given ActorSystem = useInScope(ActorSystem("test"))(_.terminate().get().discard)
      Source
        .fromPublisher(FlowAdapters.toPublisher(flow.toPublisher))
        .map(_ * 2)
        .runWith(Sink.collection[String, List[String]])
        .get()
        .sorted shouldEqual List("xx", "xx", "xx", "yy", "yy")

  test("create a flow from a simple publisher"):
    supervised:
      given ActorSystem = useInScope(ActorSystem("test"))(_.terminate().get().discard)

      val publisher: Publisher[Int] = Source
        .fromIterator(() => List(1, 2, 3).iterator)
        .map(_ * 2)
        .runWith(Sink.asPublisher[Int](false))

      Flow.fromPublisher(FlowAdapters.toFlowPublisher(publisher)).map(_ * 10).runToList() shouldEqual List(20, 40, 60)

  test("create a flow from a concurrent publisher"):
    supervised:
      given ActorSystem = useInScope(ActorSystem("test"))(_.terminate().get().discard)

      val publisher: Publisher[String] =
        Source
          .tick(0.millis, 100.millis, "x")
          .merge(Source.tick(0.millis, 200.millis, "y"))
          .take(5)
          .runWith(Sink.asPublisher[String](false))

      Flow
        .fromPublisher(FlowAdapters.toFlowPublisher(publisher))
        .map(_ * 2)
        .runToList()
        .sorted shouldEqual List("xx", "xx", "xx", "yy", "yy")
end FlowPublisherPekkoTest
