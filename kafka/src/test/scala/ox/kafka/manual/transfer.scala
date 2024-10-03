package ox.kafka.manual

import org.apache.kafka.clients.producer.ProducerRecord
import ox.*
import ox.channels.StageCapacity
import ox.kafka.*
import ox.kafka.ConsumerSettings.AutoOffsetReset

@main def transfer(): Unit =
  val sourceTopic = "t1"
  val destTopic = "t1mapped"
  val group = "group1"

  timed("transfer") {
    import KafkaStage.*

    val bootstrapServer = "localhost:29092"
    val consumerSettings = ConsumerSettings.default(group).bootstrapServers(bootstrapServer).autoOffsetReset(AutoOffsetReset.Earliest)
    val producerSettings = ProducerSettings.default.bootstrapServers(bootstrapServer)
    KafkaSource
      .subscribe(consumerSettings, sourceTopic)
      .take(10_000_000)
      .map(in => (in.value.reverse, in))
      .map((value, original) => SendPacket(ProducerRecord[String, String](destTopic, value), original))
      .mapPublishAndCommit(producerSettings)
      .runDrain()
  }
end transfer
