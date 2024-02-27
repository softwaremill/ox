package ox.kafka

import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

import scala.jdk.CollectionConverters.*

object KafkaDrain:
  private val logger = LoggerFactory.getLogger(classOf[KafkaDrain.type])

  def publish[K, V](settings: ProducerSettings[K, V]): Source[ProducerRecord[K, V]] => Unit = source =>
    publish(settings.toProducer, closeWhenComplete = true)(source)

  def publish[K, V](producer: KafkaProducer[K, V], closeWhenComplete: Boolean): Source[ProducerRecord[K, V]] => Unit = source =>
    // if sending multiple records ends in an exception, we'll receive at most one anyway; we don't want to block the
    // producers, hence creating an unbounded channel
    val producerExceptions = Channel.unlimited[Throwable]

    try
      repeatWhile {
        selectSafe(producerExceptions.receiveClause, source.receiveClause) match // bias on exceptions
          case e: ChannelClosed.Error         => throw e.toThrowable
          case ChannelClosed.Done             => false // source must be done, as producerExceptions is never done
          case producerExceptions.Received(e) => throw e
          case source.Received(record) =>
            producer.send(
              record,
              (_: RecordMetadata, exception: Exception) => {
                if exception != null then
                  logger.error("Exception when sending record", exception)
                  producerExceptions.sendSafe(exception)
              }
            )
            true
      }
    finally
      if closeWhenComplete then uninterruptible(producer.close())

  /** @return
    *   A drain, which consumes all packets from the provided `Source`.. For each packet, first all `send` messages (producer records) are
    *   sent. Then, all `commit` messages (consumer records) up to their offsets are committed.
    */
  def publishAndCommit[K, V](producerSettings: ProducerSettings[K, V]): Source[SendPacket[K, V]] => Unit =
    source => publishAndCommit(producerSettings.toProducer, closeWhenComplete = true)(source)

  /** @param producer
    *   The producer that is used to send messages.
    * @return
    *   A drain, which consumes all packets from the provided `Source`.. For each packet, first all `send` messages (producer records) are
    *   sent. Then, all `commit` messages (consumer records) up to their offsets are committed.
    */
  def publishAndCommit[K, V](producer: KafkaProducer[K, V], closeWhenComplete: Boolean): Source[SendPacket[K, V]] => Unit = source =>
    supervised {
      import KafkaStage.*
      source.mapPublishAndCommit(producer, closeWhenComplete).drain()
    }
