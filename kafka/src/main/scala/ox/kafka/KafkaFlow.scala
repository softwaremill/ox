package ox.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.ActorRef
import ox.flow.Flow
import ox.flow.FlowEmit

object KafkaFlow:
  private val logger = LoggerFactory.getLogger(classOf[KafkaFlow.type])

  def subscribe[K, V](settings: ConsumerSettings[K, V], topic: String, otherTopics: String*): Flow[ReceivedMessage[K, V]] =
    subscribe(settings.toConsumer, closeWhenComplete = true, topic, otherTopics*)

  def subscribe[K, V](
      kafkaConsumer: KafkaConsumer[K, V],
      closeWhenComplete: Boolean,
      topic: String,
      otherTopics: String*
  ): Flow[ReceivedMessage[K, V]] =
    Flow.usingEmit: emit =>
      supervised:
        val kafkaConsumerActor = KafkaConsumerWrapper(kafkaConsumer, closeWhenComplete)
        doSubscribe(kafkaConsumerActor, topic, otherTopics*)(emit)

  def subscribe[K, V](
      kafkaConsumerActor: ActorRef[KafkaConsumerWrapper[K, V]],
      topic: String,
      otherTopics: String*
  ): Flow[ReceivedMessage[K, V]] =
    Flow.usingEmit: emit =>
      doSubscribe(kafkaConsumerActor, topic, otherTopics*)(emit)

  private def doSubscribe[K, V](kafkaConsumerActor: ActorRef[KafkaConsumerWrapper[K, V]], topic: String, otherTopics: String*)(
      emit: FlowEmit[ReceivedMessage[K, V]]
  ): Unit =
    kafkaConsumerActor.tell(_.subscribe(topic :: otherTopics.toList))
    forever {
      val records = kafkaConsumerActor.ask(_.poll())
      records.forEach(r => emit(ReceivedMessage(r)))
    }.tapException(logger.error("Exception when polling for records", _))
  end doSubscribe

end KafkaFlow
