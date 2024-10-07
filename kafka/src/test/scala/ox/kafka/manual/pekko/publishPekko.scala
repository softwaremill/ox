package ox.kafka.manual.pekko

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ProducerSettings
import org.apache.pekko.kafka.scaladsl.Producer
import org.apache.pekko.stream.scaladsl.Source
import ox.{discard, get}
import ox.kafka.manual.{randomString, timed}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

@main def publishPekko(): Unit =
  val topic = "t2"
  timed("publish-pekko") {
    given system: ActorSystem = ActorSystem("publish")

    val producerSettings = ProducerSettings(system, new StringSerializer, new StringSerializer).withBootstrapServers("localhost:29092")

    val source = Source(1 to 10000000).map(_ => randomString())
    val producerRecordSource = source.map { m => new ProducerRecord[String, String](topic, m) }
    producerRecordSource.runWith(Producer.plainSink(producerSettings)).get()
    system.terminate().get().discard
  }
end publishPekko
