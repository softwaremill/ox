package ox.kafka

import io.github.embeddedkafka.EmbeddedKafka
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.channels.*
import ox.kafka.ConsumerSettings.AutoOffsetReset.Earliest
import ox.scoped

class KafkaTest extends AnyFlatSpec with Matchers with EmbeddedKafka with BeforeAndAfterAll {

  private var kafkaPort: Int = _

  override def beforeAll(): Unit =
    kafkaPort = EmbeddedKafka.start().config.kafkaPort

  override def afterAll(): Unit =
    EmbeddedKafka.stop()

  it should "receive messages from a topic" in {
    // given
    val topic = "t1"
    val group = "g1"

    // when
    publishStringMessageToKafka(topic, "msg1")
    publishStringMessageToKafka(topic, "msg2")
    publishStringMessageToKafka(topic, "msg3")

    scoped {
      // then
      val settings = ConsumerSettings.default(group).bootstrapServers(s"localhost:$kafkaPort").autoOffsetReset(Earliest)
      val source = KafkaSource.subscribe(settings, topic)

      source.receive().orThrow.value() shouldBe "msg1"

      source.receive().orThrow.value() shouldBe "msg2"
      source.receive().orThrow.value() shouldBe "msg3"

      // give a chance for a potential message to be received from Kafka & sent to the channel
      Thread.sleep(250)
      select(source.receiveClause, Default("none")) shouldBe DefaultResult("none")

      publishStringMessageToKafka(topic, "msg4")
      source.receive().orThrow.value() shouldBe "msg4"
    }
  }

  it should "send messages to topics" in {
    // given
    val topic = "t2"

    // when
    scoped {
      val settings = ProducerSettings.default.bootstrapServers(s"localhost:$kafkaPort")
      Source
        .fromIterable(List("a", "b", "c"))
        .mapAsView(msg => ProducerRecord[String, String](topic, msg))
        .pipeTo(KafkaSink.publish(settings))
    }

    // then
    given Deserializer[String] = new StringDeserializer()
    consumeNumberMessagesFrom[String](topic, 3) shouldBe List("a", "b", "c")
  }
}
