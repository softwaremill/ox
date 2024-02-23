package ox.kafka.manual

import org.apache.kafka.clients.producer.ProducerRecord
import ox.*
import ox.channels.{Source, StageCapacity}
import ox.kafka.*

@main def publish(): Unit =
  val topic = "t1"

  timed("publish") {
    supervised {
      import KafkaStage.*

      given StageCapacity = StageCapacity(16)

      val bootstrapServer = "localhost:29092"
      val settings = ProducerSettings.default.bootstrapServers(bootstrapServer)
      Source
        .unfold(())(_ => Some((randomString(), ())))
        // 100 bytes * 10000000 = 1 GB
        .take(10_000_000)
        .mapAsView(msg => ProducerRecord[String, String](topic, msg))
        .mapPublish(settings)
        .drain()
    }
  }
