# Kafka sources & drains

Dependency:

```scala
"com.softwaremill.ox" %% "kafka" % "0.0.19+3-a8305f46-SNAPSHOT"
```

`Source`s which read from a Kafka topic, mapping stages and drains which publish to Kafka topics are available through
the `KafkaSource`, `KafkaStage` and `KafkaDrain` objects. In all cases either a manually constructed instance of a
`KafkaProducer` / `KafkaConsumer` is needed, or `ProducerSettings` / `ConsumerSetttings` need to be provided with the
bootstrap servers, consumer group id, key / value serializers, etc.

To read from a Kafka topic, use:

```scala
import ox.channels.ChannelClosed
import ox.kafka.{ConsumerSettings, KafkaSource, ReceivedMessage}
import ox.kafka.ConsumerSettings.AutoOffsetReset
import ox.supervised

supervised {
  val settings = ConsumerSettings.default("my_group").bootstrapServers("localhost:9092").autoOffsetReset(AutoOffsetReset.Earliest)
  val topic = "my_topic"
  val source = KafkaSource.subscribe(settings, topic)

  source.receive(): ReceivedMessage[String, String] | ChannelClosed
}
```

To publish data to a Kafka topic:

```scala
import ox.channels.Source
import ox.kafka.{ProducerSettings, KafkaDrain}
import ox.supervised
import org.apache.kafka.clients.producer.ProducerRecord

supervised {
  val settings = ProducerSettings.default.bootstrapServers("localhost:9092")
  Source
    .fromIterable(List("a", "b", "c"))
    .mapAsView(msg => ProducerRecord[String, String]("my_topic", msg))
    .applied(KafkaDrain.publish(settings))
}
```

To publish data and commit offsets of messages, basing on which the published data is computed:

```scala
import ox.kafka.{ConsumerSettings, KafkaDrain, KafkaSource, ProducerSettings, SendPacket}
import ox.kafka.ConsumerSettings.AutoOffsetReset
import ox.supervised
import org.apache.kafka.clients.producer.ProducerRecord

supervised {
  val consumerSettings = ConsumerSettings.default("my_group").bootstrapServers("localhost:9092").autoOffsetReset(AutoOffsetReset.Earliest)
  val producerSettings = ProducerSettings.default.bootstrapServers("localhost:9092")
  val sourceTopic = "source_topic"
  val destTopic = "dest_topic"

  KafkaSource
    .subscribe(consumerSettings, sourceTopic)
    .map(in => (in.value.toLong * 2, in))
    .map((value, original) => SendPacket(ProducerRecord[String, String](destTopic, value.toString), original))
    .applied(KafkaDrain.publishAndCommit(producerSettings))
}
```

The offsets are committed every second in a background process.

To publish data as a mapping stage:

```scala
import ox.channels.Source
import ox.kafka.{ProducerSettings, KafkaDrain}
import ox.kafka.KafkaStage.*
import ox.supervised
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}

supervised {
  val settings = ProducerSettings.default.bootstrapServers("localhost:9092")
  val metadatas: Source[RecordMetadata] = Source
    .fromIterable(List("a", "b", "c"))
    .mapAsView(msg => ProducerRecord[String, String]("my_topic", msg))
    .mapPublish(settings)
  
  // process the metadatas source further
}
```
