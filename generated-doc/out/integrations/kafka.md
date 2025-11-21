# Kafka flows

Dependency:

```scala
"com.softwaremill.ox" %% "kafka" % "1.0.2"
```

`Flow`s which read from a Kafka topic, mapping stages and drains which publish to Kafka topics are available through
the `KafkaFlow`, `KafkaStage` and `KafkaDrain` objects. 

In all cases kafka producers and consumers can be provided:
* by manually creating (and closing) an instance of a `KafkaProducer` / `KafkaConsumer`
* through a `ProducerSettings` / `ConsumerSettings`, with the bootstrap servers, consumer group id, key/value
  serializers, etc. The lifetime is then managed by the flow operators.
* through a thread-safe wrapper on a consumer (`ActorRef[KafkaConsumerWrapper[K, V]]`), for which the lifetime is bound
  to the current concurrency scope

## Reading from Kafka

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

## Publishing to Kafka

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

## Reading & publishing to Kafka with offset commits

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
import ox.*
import org.apache.kafka.clients.producer.ProducerRecord

val consumerSettings = ConsumerSettings.default("my_group")
  .bootstrapServers("localhost:9092").autoOffsetReset(AutoOffsetReset.Earliest)
val producerSettings = ProducerSettings.default.bootstrapServers("localhost:9092")
val sourceTopic = "source_topic"
val destTopic = "dest_topic"

supervised:
  // the consumer is shared between the subscribe & offset stages
  // its lifetime is bound to the current concurrency scope
  val consumer = consumerSettings.toThreadSafeConsumerWrapper
  KafkaFlow
    .subscribe(consumer, sourceTopic)
    .map(in => (in.value.toLong * 2, in))
    .map((value, original) => 
      SendPacket(ProducerRecord[String, String](destTopic, value.toString), original))
    .pipe(KafkaDrain.runPublishAndCommit(producerSettings, consumer))
```

The offsets are committed every second in a background process.

## Reading from Kafka, processing data & committing offsets

Offsets can also be committed after the data has been processed, without producing any records to write to a topic.
For that, we can use the `runCommit` drain, or the `mapCommit` stage, both of which work with a `Flow[CommitPacket]`:

```scala
import ox.kafka.{ConsumerSettings, KafkaDrain, KafkaFlow, CommitPacket}
import ox.kafka.ConsumerSettings.AutoOffsetReset
import ox.*

val consumerSettings = ConsumerSettings.default("my_group")
  .bootstrapServers("localhost:9092").autoOffsetReset(AutoOffsetReset.Earliest)
val sourceTopic = "source_topic"

supervised:
  // the consumer is shared between the subscribe & offset stages
  // its lifetime is bound to the current concurrency scope
  val consumer = consumerSettings.toThreadSafeConsumerWrapper
  KafkaFlow
    .subscribe(consumer, sourceTopic)
    .mapPar(10) { in => 
      // process the message, e.g. send an HTTP request
      CommitPacket(in)
    }
    .pipe(KafkaDrain.runCommit(consumer))
```