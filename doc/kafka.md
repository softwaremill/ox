# Kafka sources & drains

Dependency:

```scala
"com.softwaremill.ox" %% "kafka" % "@VERSION@"
```

`Source`s which read from a Kafka topic, mapping stages and drains which publish to Kafka topics are available through
the `KafkaSource`, `KafkaStage` and `KafkaDrain` objects. In all cases either a manually constructed instance of a
`KafkaProducer` / `KafkaConsumer` is needed, or `ProducerSettings` / `ConsumerSetttings` need to be provided with the
bootstrap servers, consumer group id, key / value serializers, etc.

To read from a Kafka topic, use:

```scala
import ox.channel.ChannelClosed
import ox.kafka.{ConsumerSettings, KafkaSource}
import ox.kafka.ConsumerSettings.AutoOffsetReset.Earliest
import ox.supervised
import org.apache.kafka.clients.consumer.ConsumerRecord

supervised {
  val settings = ConsumerSettings.default("my_group").bootstrapServers("localhost:9092").autoOffsetReset(Earliest)
  val source = KafkaSource.subscribe(settings, topic)

  source.receive(): ConsumerRecord[String, String] | ChannelClosed
}
```

To publish data to a Kafka topic:

```scala
import ox.channel.Source
import ox.kafka.{ProducerSettings, KafkaSink}
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
import ox.kafka.{KafkaSink, KafkaSource, ProducerSettings, SendPacket}
import ox.supervised
import org.apache.kafka.clients.producer.ProducerRecord

supervised {
  val consumerSettings = ConsumerSettings.default("my_group").bootstrapServers("localhost:9092").autoOffsetReset(Earliest)
  val producerSettings = ProducerSettings.default.bootstrapServers("localhost:9092")

  KafkaSource
    .subscribe(consumerSettings, sourceTopic)
    .map(in => (in.value().toLong * 2, in))
    .map((value, original) => SendPacket(ProducerRecord[String, String](destTopic, value.toString), original))
    .applied(KafkaDrain.publishAndCommit(consumerSettings, producerSettings))
}
```

The offsets are committed every second in a background process.

To publish data as a mapping stage:

```scala
import ox.channel.Source
import ox.kafka.{ProducerSettings, KafkaSink}
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
