# Kafka flows

Dependency:

```scala
"com.softwaremill.ox" %% "kafka" % "0.7.3"
```

`Flow`s which read from a Kafka topic, mapping stages and drains which publish to Kafka topics are available through
the `KafkaFlow`, `KafkaStage` and `KafkaDrain` objects. In all cases either a manually constructed instance of a
`KafkaProducer` / `KafkaConsumer` is needed, or `ProducerSettings` / `ConsumerSetttings` need to be provided with the
bootstrap servers, consumer group id, key / value serializers, etc.

To read from a Kafka topic, use:

```scala
import ox.kafka.{ConsumerSettings, KafkaFlow, ReceivedMessage}
import ox.kafka.ConsumerSettings.AutoOffsetReset

val settings = ConsumerSettings.default("my_group").bootstrapServers("localhost:9092")
  .autoOffsetReset(AutoOffsetReset.Earliest)
val topic = "my_topic"
  
val source = KafkaFlow.subscribe(settings, topic)
  .runForeach { (msg: ReceivedMessage[String, String]) => ??? }
```

To publish data to a Kafka topic:

```scala
import ox.flow.Flow
import ox.kafka.{ProducerSettings, KafkaDrain}
import ox.pipe
import org.apache.kafka.clients.producer.ProducerRecord

val settings = ProducerSettings.default.bootstrapServers("localhost:9092")
Flow
  .fromIterable(List("a", "b", "c"))
  .map(msg => ProducerRecord[String, String]("my_topic", msg))
  .pipe(KafkaDrain.runPublish(settings))
```

Quite often data to be published to a topic (`topic1`) is computed basing on data received from another topic 
(`topic2`). In such a case, it's possible to commit messages from `topic2`, after the messages to `topic1` are 
successfully published. 

In order to do so, a `Flow[SendPacket]` needs to be created. The definition of `SendPacket` is:

```scala
import org.apache.kafka.clients.producer.ProducerRecord
import ox.kafka.ReceivedMessage

case class SendPacket[K, V](
  send: List[ProducerRecord[K, V]], 
  commit: List[ReceivedMessage[_, _]])
```

The `send` list contains the messages to be sent (each message is a Kafka `ProducerRecord`). The `commit` list contains
the messages, basing on which the data to be sent was computed. These are the received messages, as produced by a 
`KafkaFlow`. When committing, for each topic-partition that appears in the received messages, the maximum offset is
computed. For example:

```scala
import ox.kafka.{ConsumerSettings, KafkaDrain, KafkaFlow, ProducerSettings, SendPacket}
import ox.kafka.ConsumerSettings.AutoOffsetReset
import ox.pipe
import org.apache.kafka.clients.producer.ProducerRecord

val consumerSettings = ConsumerSettings.default("my_group")
  .bootstrapServers("localhost:9092").autoOffsetReset(AutoOffsetReset.Earliest)
val producerSettings = ProducerSettings.default.bootstrapServers("localhost:9092")
val sourceTopic = "source_topic"
val destTopic = "dest_topic"

KafkaFlow
  .subscribe(consumerSettings, sourceTopic)
  .map(in => (in.value.toLong * 2, in))
  .map((value, original) => 
    SendPacket(ProducerRecord[String, String](destTopic, value.toString), original))
  .pipe(KafkaDrain.runPublishAndCommit(producerSettings))
```

The offsets are committed every second in a background process.

To publish data as a mapping stage:

```scala
import ox.flow.Flow
import ox.kafka.ProducerSettings
import ox.kafka.KafkaStage.*
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}

val settings = ProducerSettings.default.bootstrapServers("localhost:9092")
val metadatas: Flow[RecordMetadata] = Flow
  .fromIterable(List("a", "b", "c"))
  .map(msg => ProducerRecord[String, String]("my_topic", msg))
  .mapPublish(settings)

// process & run the metadatas flow further
```
