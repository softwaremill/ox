package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer}
import org.slf4j.LoggerFactory
import ox.*
import ox.flow.Flow

object KafkaSource:
  private val logger = LoggerFactory.getLogger(classOf[KafkaSource.type])

  def subscribe[K, V](settings: ConsumerSettings[K, V], topic: String, otherTopics: String*): Flow[ReceivedMessage[K, V]] =
    subscribe(settings.toConsumer, closeWhenComplete = true, topic, otherTopics*)

  def subscribe[K, V](
      kafkaConsumer: KafkaConsumer[K, V],
      closeWhenComplete: Boolean,
      topic: String,
      otherTopics: String*
  ): Flow[ReceivedMessage[K, V]] =
    Flow.fromUsingSink: sink =>
      supervised:
        val kafkaConsumerActor = KafkaConsumerWrapper(kafkaConsumer, closeWhenComplete)
        kafkaConsumerActor.tell(_.subscribe(topic :: otherTopics.toList))
        forever {
          val records = kafkaConsumerActor.ask(_.poll())
          records.forEach(r => sink(ReceivedMessage(kafkaConsumerActor, r)))
        }.tapException(logger.error("Exception when polling for records", _))

end KafkaSource
