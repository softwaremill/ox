package ox.flow.reactive

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.reactivestreams.FlowAdapters
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
end FlowPublisherPekkoTest
