package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object KafkaSink:
  private val logger = LoggerFactory.getLogger(classOf[KafkaSink.type])

  def publish[K, V](settings: ProducerSettings[K, V])(using StageCapacity, Ox): Sink[ProducerRecord[K, V]] =
    publish(settings.toProducer, closeWhenComplete = true)

  def publish[K, V](producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using StageCapacity, Ox): Sink[ProducerRecord[K, V]] =
    val c = StageCapacity.newChannel[ProducerRecord[K, V]]

    fork {
      try
        repeatWhile {
          c.receive() match
            case e: ChannelClosed.Error =>
              logger.debug(s"Stopping publishing: upstream closed due to an error ($e).")
              false
            case ChannelClosed.Done =>
              logger.debug(s"Stopping publishing: upstream done.")
              false
            case record: ProducerRecord[K, V] @unchecked =>
              tapException {
                producer.send(
                  record,
                  (_: RecordMetadata, exception: Exception) => {
                    if exception != null then exceptionWhenSendingRecord(c, exception)
                  }
                )
              }(exceptionWhenSendingRecord(c, _))
              true
        }
      finally
        if closeWhenComplete then
          logger.debug("Closing the Kafka producer")
          uninterruptible(producer.close())
    }

    c

  /** @return
    *   A sink, which accepts commit packets. For each packet, first all messages (producer records) are sent. Then, all messages up to the
    *   offsets of the consumer messages are committed.
    */
  def publishAndCommit[K, V](producerSettings: ProducerSettings[K, V])(using StageCapacity, Ox): Sink[SendPacket[K, V]] =
    publishAndCommit(producerSettings.toProducer, closeWhenComplete = true)

  /** @param producer
    *   The producer that is used to send messages.
    * @return
    *   A sink, which accepts send packets. For each packet, first all `send` messages (producer records) are sent. Then, all `commit`
    *   messages (consumer records) up to their offsets are committed.
    */
  def publishAndCommit[K, V](producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using
      StageCapacity,
      Ox
  ): Sink[SendPacket[K, V]] =
    val c = StageCapacity.newChannel[SendPacket[K, V]]
    val toCommit = Channel[SendPacket[_, _]](128)

    fork {
      try
        // starting a nested scope, so that the committer is interrupted when the main process ends
        scoped {
          // committer
          fork(tapException(doCommit(toCommit)) { e =>
            logger.error("Exception when committing offsets", e)
            c.error(e)
          })

          repeatWhile {
            c.receive() match
              case e: ChannelClosed.Error =>
                logger.debug(s"Stopping publishing: upstream closed due to an error ($e).")
                false
              case ChannelClosed.Done =>
                logger.debug(s"Stopping publishing: upstream done.")
                false
              case packet: SendPacket[K, V] @unchecked =>
                tapException(sendPacket(producer, packet, toCommit, exceptionWhenSendingRecord(c, _)))(exceptionWhenSendingRecord(c, _))
                true
          }
        }
      finally
        if closeWhenComplete then
          logger.debug("Closing the Kafka producer")
          uninterruptible(producer.close())
    }

    c

  private def sendPacket[K, V](
      producer: KafkaProducer[K, V],
      packet: SendPacket[K, V],
      toCommit: Sink[SendPacket[_, _]],
      onSendException: Exception => Unit
  ): Unit =
    val leftToSend = new AtomicInteger(packet.send.size)
    packet.send.foreach { toSend =>
      producer.send(
        toSend,
        (_: RecordMetadata, exception: Exception) => {
          if exception == null
          then
            if leftToSend.decrementAndGet() == 0 then toCommit.send(packet)
            else onSendException(exception)
        }
      )
    }

  private def doCommit(packets: Source[SendPacket[_, _]])(using Ox) =
    val commitInterval = 1.second
    val ticks = Source.tick(commitInterval)
    val toCommit = mutable.Map[TopicPartition, Long]()
    var consumer: Sink[KafkaConsumerRequest[_, _]] = null // assuming all packets come from the same consumer

    forever {
      select(ticks, packets).orThrow match
        case () =>
          if consumer != null && toCommit.nonEmpty then
            logger.trace(s"Committing ${toCommit.size} offsets.")
            consumer.send(KafkaConsumerRequest.Commit(toCommit.toMap))
            toCommit.clear()
        case packet: SendPacket[_, _] =>
          packet.commit.foreach { receivedMessage =>
            if consumer == null then consumer = receivedMessage.consumer.asInstanceOf[Sink[KafkaConsumerRequest[_, _]]]
            val tp = new TopicPartition(receivedMessage.topic, receivedMessage.partition)
            toCommit.updateWith(tp) {
              case Some(offset) => Some(math.max(offset, receivedMessage.offset))
              case None         => Some(receivedMessage.offset)
            }
          }
    }

  private def exceptionWhenSendingRecord(s: Sink[_], e: Throwable) =
    logger.error("Exception when sending record", e)
    s.error(e)

case class SendPacket[K, V](send: List[ProducerRecord[K, V]], commit: List[ReceivedMessage[_, _]])

object SendPacket:
  def apply[K, V](send: ProducerRecord[K, V], commit: ReceivedMessage[_, _]): SendPacket[K, V] =
    SendPacket(List(send), List(commit))

  def apply[K, V](send: List[ProducerRecord[K, V]], commit: ReceivedMessage[_, _]): SendPacket[K, V] =
    SendPacket(send, List(commit))
