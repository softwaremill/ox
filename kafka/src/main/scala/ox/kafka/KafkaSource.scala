package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

object KafkaSource:
  private val logger = LoggerFactory.getLogger(classOf[KafkaSource.type])

  def subscribe[K, V](settings: ConsumerSettings[K, V], topic: String, otherTopics: String*)(using
      StageCapacity,
      Ox,
      IO
  ): Source[ReceivedMessage[K, V]] =
    subscribe(settings.toConsumer, closeWhenComplete = true, topic, otherTopics: _*)

  def subscribe[K, V](kafkaConsumer: KafkaConsumer[K, V], closeWhenComplete: Boolean, topic: String, otherTopics: String*)(using
      StageCapacity,
      Ox,
      IO
  ): Source[ReceivedMessage[K, V]] = subscribe(KafkaConsumerWrapper(kafkaConsumer, closeWhenComplete), topic, otherTopics: _*)

  def subscribe[K, V](kafkaConsumer: ActorRef[KafkaConsumerWrapper[K, V]], topic: String, otherTopics: String*)(using
      StageCapacity,
      Ox,
      IO
  ): Source[ReceivedMessage[K, V]] =
    kafkaConsumer.tell(_.subscribe(topic :: otherTopics.toList))

    val c = StageCapacity.newChannel[ReceivedMessage[K, V]]
    fork {
      try
        forever {
          val records = kafkaConsumer.ask(_.poll())
          records.forEach(r => c.send(ReceivedMessage(kafkaConsumer, r)))
        }
      catch
        case t: Throwable =>
          logger.error("Exception when polling for records", t)
          c.errorOrClosed(t)
    }
    c
