package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer}
import ox.{Ox, fork}
import ox.channels.{Source, StageCapacity}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object KafkaSource:
  def subscribe[K, V](settings: ConsumerSettings[K, V], topic: String, otherTopics: String*)(using
      StageCapacity,
      Ox
  ): Source[ConsumerRecord[K, V]] =
    subscribe(settings.toConsumer, closeWhenComplete = true, topic, otherTopics: _*)

  def subscribe[K, V](kafkaConsumer: KafkaConsumer[K, V], closeWhenComplete: Boolean, topic: String, otherTopics: String*)(using
      StageCapacity,
      Ox
  ): Source[ConsumerRecord[K, V]] =
    kafkaConsumer.subscribe((topic :: otherTopics.toList).asJava)
    val c = StageCapacity.newChannel[ConsumerRecord[K, V]]

    fork {
      try
        while true do
          val records = kafkaConsumer.poll(java.time.Duration.ofMillis(100))
          records.forEach(c.send)
      catch case NonFatal(e) => c.error(e)
      finally if closeWhenComplete then kafkaConsumer.close()
    }

    c
