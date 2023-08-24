package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object KafkaDrain:
  def publish[K, V](settings: ProducerSettings[K, V]): Source[ProducerRecord[K, V]] => Unit = source =>
    publish(settings.toProducer, closeWhenComplete = true)(source)

  def publish[K, V](producer: KafkaProducer[K, V], closeWhenComplete: Boolean): Source[ProducerRecord[K, V]] => Unit = source =>
    // if sending multiple records ends in an exception, we'll receive at most one anyway; we don't want to block the
    // producers, hence creating an unbounded channel
    val producerExceptions = Channel[Exception](Int.MaxValue)

    try
      repeatWhile {
        select(producerExceptions.receiveClause, source.receiveOrDoneClause) match // bias on exceptions
          case e: ChannelClosed.Error         => throw e.toThrowable
          case ChannelClosed.Done             => false // source must be done, as producerExceptions is never done
          case producerExceptions.Received(e) => throw e
          case source.Received(record) =>
            producer.send(
              record,
              (_: RecordMetadata, exception: Exception) => {
                if exception != null then producerExceptions.send(exception)
              }
            )
            true
      }
    finally
      if closeWhenComplete then uninterruptible(producer.close())
