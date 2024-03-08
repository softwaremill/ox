package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecord, ConsumerRecords, KafkaConsumer}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

object KafkaSource:
  private val logger = LoggerFactory.getLogger(classOf[KafkaSource.type])

  def subscribe[K, V](settings: ConsumerSettings[K, V], topic: String, otherTopics: String*)(using
      StageCapacity,
      Ox
  ): Source[ReceivedMessage[K, V]] =
    subscribe(settings.toConsumer, closeWhenComplete = true, topic, otherTopics: _*)

  def subscribe[K, V](kafkaConsumer: KafkaConsumer[K, V], closeWhenComplete: Boolean, topic: String, otherTopics: String*)(using
      StageCapacity,
      Ox
  ): Source[ReceivedMessage[K, V]] = subscribe(KafkaConsumerActor(kafkaConsumer, closeWhenComplete), topic, otherTopics: _*)

  def subscribe[K, V](kafkaConsumer: Sink[KafkaConsumerRequest[K, V]], topic: String, otherTopics: String*)(using
      StageCapacity,
      Ox
  ): Source[ReceivedMessage[K, V]] =
    kafkaConsumer.send(KafkaConsumerRequest.Subscribe(topic :: otherTopics.toList))

    val c = StageCapacity.newChannel[ReceivedMessage[K, V]]

    fork {
      try
        val pollResults = Channel.rendezvous[ConsumerRecords[K, V]]
        forever {
          kafkaConsumer.send(KafkaConsumerRequest.Poll(pollResults))
          val records = pollResults.receive()
          records.forEach(r => c.send(ReceivedMessage(kafkaConsumer, r)))
        }
      catch
        case t: Throwable =>
          logger.error("Exception when polling for records", t)
          c.errorSafe(t)
    }

    c
