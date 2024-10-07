package ox.kafka.manual

import org.apache.kafka.clients.producer.ProducerRecord
import ox.*

import ox.kafka.*
import ox.flow.Flow

@main def publish(): Unit =
  val topic = "t1"

  timed("publish") {
    import KafkaStage.*

    val bootstrapServer = "localhost:29092"
    val settings = ProducerSettings.default.bootstrapServers(bootstrapServer)
    Flow
      .unfold(())(_ => Some((randomString(), ())))
      // 100 bytes * 10000000 = 1 GB
      .take(10_000_000)
      .map(msg => ProducerRecord[String, String](topic, msg))
      .mapPublish(settings)
      .runDrain()
  }
end publish
