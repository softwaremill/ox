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
  private val logger = LoggerFactory.getLogger(classOf[KafkaDrain.type])

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
                if exception != null then
                  logger.error("Exception when sending record", exception)
                  producerExceptions.send(exception)
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
    val exceptions = Channel[Throwable](Int.MaxValue)
    val toCommit = Channel[SendPacket[_, _]](128)

    try
      // starting a nested scope, so that the committer is interrupted when the main process ends
      scoped {
        // committer
        fork(tapException(doCommit(toCommit)) { e =>
          logger.error("Exception when committing offsets", e)
          exceptions.send(e)
        })

        repeatWhile {
          select(exceptions.receiveClause, source.receiveOrDoneClause) match
            case e: ChannelClosed.Error =>
              logger.debug(s"Stopping publishing: upstream closed due to an error ($e).")
              throw e.toThrowable
            case ChannelClosed.Done =>
              logger.debug(s"Stopping publishing: upstream done.")
              false
            case exceptions.Received(e) =>
              throw e
            case source.Received(packet) =>
              sendPacket(producer, packet, toCommit, exceptions)
              true
        }
      }
    finally
      if closeWhenComplete then
        logger.debug("Closing the Kafka producer")
        uninterruptible(producer.close())

  private def sendPacket[K, V](
      producer: KafkaProducer[K, V],
      packet: SendPacket[K, V],
      toCommit: Sink[SendPacket[_, _]],
      exceptions: Sink[Throwable]
  ): Unit =
    val leftToSend = new AtomicInteger(packet.send.size)
    packet.send.foreach { toSend =>
      producer.send(
        toSend,
        (_: RecordMetadata, exception: Exception) => {
          if exception == null
          then { if leftToSend.decrementAndGet() == 0 then toCommit.send(packet) }
          else
            logger.error("Exception when sending record", exception)
            exceptions.send(exception)
        }
      )
    }
