package ox.kafka

import io.github.embeddedkafka.EmbeddedKafka
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.channels.*
import ox.kafka.ConsumerSettings.AutoOffsetReset.Earliest
import ox.*

import scala.concurrent.duration.*
import ox.flow.Flow

class KafkaTest extends AnyFlatSpec with Matchers with EmbeddedKafka with BeforeAndAfterAll:
  private var bootstrapServer: String = _

  override def beforeAll(): Unit =
    bootstrapServer = s"localhost:${EmbeddedKafka.start().config.kafkaPort}"

  override def afterAll(): Unit =
    EmbeddedKafka.stop()

  "source" should "receive messages from a topic" in {
    // given
    val topic = "t1"
    val group = "g1"

    // when
    publishStringMessageToKafka(topic, "msg1")
    publishStringMessageToKafka(topic, "msg2")
    publishStringMessageToKafka(topic, "msg3")

    supervised {
      // then
      val settings = ConsumerSettings.default(group).bootstrapServers(bootstrapServer).autoOffsetReset(Earliest)
      val source = KafkaFlow.subscribe(settings, topic).runToChannel()

      source.receive().value shouldBe "msg1"
      source.receive().value shouldBe "msg2"
      source.receive().value shouldBe "msg3"

      // give a chance for a potential message to be received from Kafka & sent to the channel
      sleep(250.millis)
      select(source.receiveClause, Default("none")) shouldBe DefaultResult("none")

      publishStringMessageToKafka(topic, "msg4")
      source.receive().value shouldBe "msg4"
    }
  }

  "stage" should "publish messages to a topic" in {
    // given
    val topic = "t2"
    val count = 1000
    val msgs = (1 to count).map(n => s"msg$n").toList

    // when
    val metadatas = supervised {
      import KafkaStage.*

      val settings = ProducerSettings.default.bootstrapServers(bootstrapServer)
      Flow
        .fromIterable(msgs)
        .map(msg => ProducerRecord[String, String](topic, msg))
        .mapPublish(settings)
        .runToList()
    }

    // then
    metadatas.map(_.offset()) shouldBe (0 until count).toList

    given Deserializer[String] = new StringDeserializer()
    consumeNumberMessagesFrom[String](topic, count, timeout = 30.seconds) shouldBe msgs
  }

  it should "commit offsets of processed messages" in {
    // given
    val sourceTopic = "t3_1"
    val destTopic = "t3_2"
    val group1 = "g3_1"
    val group2 = "g3_2"

    val consumerSettings = ConsumerSettings.default(group1).bootstrapServers(bootstrapServer).autoOffsetReset(Earliest)
    val producerSettings = ProducerSettings.default.bootstrapServers(bootstrapServer)

    // when
    publishStringMessageToKafka(sourceTopic, "10")
    publishStringMessageToKafka(sourceTopic, "25")
    publishStringMessageToKafka(sourceTopic, "92")

    val metadatas = BufferCapacity.newChannel[RecordMetadata]

    supervised {
      // then
      fork {
        import KafkaStage.*

        KafkaFlow
          .subscribe(consumerSettings, sourceTopic)
          .map(in => (in.value.toLong * 2, in))
          .map((value, original) => SendPacket(ProducerRecord[String, String](destTopic, value.toString), original))
          .mapPublishAndCommit(producerSettings)
          .runPipeToSink(metadatas, propagateDone = false)
      }

      val inDest = KafkaFlow.subscribe(consumerSettings, destTopic).runToChannel()
      inDest.receive().value shouldBe "20"
      inDest.receive().value shouldBe "50"
      inDest.receive().value shouldBe "184"

      // giving the commit process a chance to commit
      sleep(2.seconds)

      // checking the metadata
      metadatas.receive().offset() shouldBe 0L
      metadatas.receive().offset() shouldBe 1L
      metadatas.receive().offset() shouldBe 2L

      // interrupting the stream processing
    }

    // sending some more messages to source
    publishStringMessageToKafka(sourceTopic, "4")

    supervised {
      // reading from source, using the same consumer group as before, should start from the last committed offset
      val inSource = KafkaFlow.subscribe(consumerSettings, sourceTopic).runToChannel()
      inSource.receive().value shouldBe "4"

      // while reading using another group, should start from the earliest offset
      val inSource2 = KafkaFlow.subscribe(consumerSettings.groupId(group2), sourceTopic).runToChannel()
      inSource2.receive().value shouldBe "10"
    }
  }

  "drain" should "publish messages to a topic" in {
    // given
    val topic = "t4"

    // when
    supervised {
      val settings = ProducerSettings.default.bootstrapServers(bootstrapServer)
      Flow
        .fromIterable(List("a", "b", "c"))
        .map(msg => ProducerRecord[String, String](topic, msg))
        .pipe(KafkaDrain.runPublish(settings))
    }

    // then
    given Deserializer[String] = new StringDeserializer()
    consumeNumberMessagesFrom[String](topic, 3, timeout = 30.seconds) shouldBe List("a", "b", "c")
  }

  it should "commit offsets of processed messages" in {
    // given
    val sourceTopic = "t5_1"
    val destTopic = "t5_2"
    val group1 = "g5_1"
    val group2 = "g5_2"

    val consumerSettings = ConsumerSettings.default(group1).bootstrapServers(bootstrapServer).autoOffsetReset(Earliest)
    val producerSettings = ProducerSettings.default.bootstrapServers(bootstrapServer)

    // when
    publishStringMessageToKafka(sourceTopic, "10")
    publishStringMessageToKafka(sourceTopic, "25")
    publishStringMessageToKafka(sourceTopic, "92")

    supervised {
      // then
      fork {
        KafkaFlow
          .subscribe(consumerSettings, sourceTopic)
          .map(in => (in.value.toLong * 2, in))
          .map((value, original) => SendPacket(ProducerRecord[String, String](destTopic, value.toString), original))
          .pipe(KafkaDrain.runPublishAndCommit(producerSettings))
      }

      val inDest = KafkaFlow.subscribe(consumerSettings, destTopic).runToChannel()
      inDest.receive().value shouldBe "20"
      inDest.receive().value shouldBe "50"
      inDest.receive().value shouldBe "184"

      // giving the commit process a chance to commit
      sleep(2.seconds)

      // interrupting the stream processing
    }

    // sending some more messages to source
    publishStringMessageToKafka(sourceTopic, "4")

    supervised {
      // reading from source, using the same consumer group as before, should start from the last committed offset
      val inSource = KafkaFlow.subscribe(consumerSettings, sourceTopic).runToChannel()
      inSource.receive().value shouldBe "4"

      // while reading using another group, should start from the earliest offset
      val inSource2 = KafkaFlow.subscribe(consumerSettings.groupId(group2), sourceTopic).runToChannel()
      inSource2.receive().value shouldBe "10"
    }
  }
end KafkaTest
