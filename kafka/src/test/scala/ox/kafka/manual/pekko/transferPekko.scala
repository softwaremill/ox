package ox.kafka.manual.pekko

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.scaladsl.Consumer.DrainingControl
import org.apache.pekko.kafka.scaladsl.{Committer, Consumer, Producer}
import org.apache.pekko.kafka.{CommitterSettings, ConsumerSettings, ProducerMessage, ProducerSettings, Subscriptions}
import ox.kafka.manual.timed

import scala.concurrent.Await
import scala.concurrent.duration.Duration

@main def transferPekko(): Unit =
  val sourceTopic = "t2"
  val destTopic = "t2mapped"
  val group = "group2"

  timed("transfer-pekko") {
    given system: ActorSystem = ActorSystem("transfer")

    val producerSettings = ProducerSettings(system, new StringSerializer, new StringSerializer).withBootstrapServers("localhost:29092")
    val consumerSettings = ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers("localhost:29092")
      .withGroupId(group)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    val stream = Consumer
      .committableSource(consumerSettings, Subscriptions.topics(sourceTopic))
      .take(10_000_000)
      .map { msg =>
        ProducerMessage.single(
          new ProducerRecord[String, String](destTopic, msg.record.key(), msg.record.value().reverse),
          msg.committableOffset
        )
      }
      .via(Producer.flexiFlow(producerSettings))
      .map(_.passThrough)
      .toMat(Committer.sink(CommitterSettings(system)))(DrainingControl.apply)
      .run()
      .streamCompletion

    Await.result(stream, Duration.Inf)
    val _ = Await.result(system.terminate(), Duration.Inf)
  }
