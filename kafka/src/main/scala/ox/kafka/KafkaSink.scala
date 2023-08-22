package ox.kafka

import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import ox.*
import ox.channels.{ChannelClosed, Sink, StageCapacity}

object KafkaSink:
  def publish[K, V](settings: ProducerSettings[K, V])(using StageCapacity, Ox): Sink[ProducerRecord[K, V]] =
    val producer = new KafkaProducer(settings.toProperties, settings.keySerializer, settings.valueSerializer)
    publish(producer, closeWhenComplete = true)

  def publish[K, V](producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using StageCapacity, Ox): Sink[ProducerRecord[K, V]] =
    val c = StageCapacity.newChannel[ProducerRecord[K, V]]

    fork {
      try
        repeatWhile {
          c.receive() match
            case e: ChannelClosed.Error => c.error(e.toThrowable); false
            case ChannelClosed.Done     => false
            case record: ProducerRecord[K, V] @unchecked =>
              producer.send(
                record,
                (_: RecordMetadata, exception: Exception) => {
                  if exception != null then c.error(exception)
                }
              ); true
        }
      finally if closeWhenComplete then producer.close()
    }

    c
