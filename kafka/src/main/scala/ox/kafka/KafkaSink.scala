package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import ox.*
import ox.channels.*

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object KafkaSink:
  def publish[K, V](settings: ProducerSettings[K, V])(using StageCapacity, Ox): Sink[ProducerRecord[K, V]] =
    publish(settings.toProducer, closeWhenComplete = true)

  def publish[K, V](producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using StageCapacity, Ox): Sink[ProducerRecord[K, V]] =
    val c = StageCapacity.newChannel[ProducerRecord[K, V]]

    fork {
      try
        repeatWhile {
          c.receive() match
            case e: ChannelClosed.Error => c.error(e.toThrowable); false
            case ChannelClosed.Done     => false
            case record: ProducerRecord[K, V] @unchecked =>
              tapException {
                producer.send(
                  record,
                  (_: RecordMetadata, exception: Exception) => {
                    if exception != null then c.error(exception)
                  }
                )
              }(c.error)
              true
        }
      finally if closeWhenComplete then producer.close()
    }

    c

  /** @return
    *   A sink, which accepts commit packets. For each packet, first all messages (producer records) are sent. Then, all messages up to the
    *   offsets of the consumer messages are committed.
    */
  def publishAndCommit[K, V](consumerSettings: ConsumerSettings[K, V], producerSettings: ProducerSettings[K, V])(using
      StageCapacity,
      Ox
  ): Sink[SendPacket[K, V]] =
    publishAndCommit(consumerSettings.toConsumer, producerSettings.toProducer, closeWhenComplete = true)

  /** @param consumer
    *   The consumer that is used to commit offsets.
    * @param producer
    *   The producer that is used to send messages.
    * @return
    *   A sink, which accepts send packets. For each packet, first all `send` messages (producer records) are sent. Then, all `commit`
    *   messages (consumer records) up to their offsets are committed.
    */
  def publishAndCommit[K, V](consumer: KafkaConsumer[K, V], producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using
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
          fork(tapException(doCommit(consumer, toCommit))(c.error))

          repeatWhile {
            c.receive() match
              case e: ChannelClosed.Error => c.error(e.toThrowable); false
              case ChannelClosed.Done     => false
              case packet: SendPacket[K, V] @unchecked =>
                tapException(sendPacket(producer, packet, toCommit, c.error))(c.error)
                true
          }
        }
      finally
        if closeWhenComplete then
          consumer.close()
          producer.close()
    }

    c

  private def sendPacket[K, V](
      producer: KafkaProducer[K, V],
      packet: SendPacket[K, V],
      toCommit: Sink[SendPacket[_, _]],
      onSendException: Exception => Unit
  ) =
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

  private def doCommit(consumer: KafkaConsumer[_, _], packets: Source[SendPacket[_, _]])(using Ox) =
    val commitInterval = 1.second
    val ticks = Source.tick(commitInterval)
    val toCommit = mutable.Map[TopicPartition, Long]()

    forever {
      select(ticks, packets).orThrow match
        case () =>
          consumer.commitSync(toCommit.view.mapValues(new OffsetAndMetadata(_)).toMap.asJava)
          toCommit.clear()
        case packet: SendPacket[_, _] =>
          packet.commit.foreach { record =>
            val tp = new TopicPartition(record.topic(), record.partition())
            toCommit.updateWith(tp) {
              case Some(offset) => Some(math.max(offset, record.offset()))
              case None         => Some(record.offset())
            }
          }
    }

case class SendPacket[K, V](send: List[ProducerRecord[K, V]], commit: List[ConsumerRecord[_, _]])

object SendPacket:
  def apply[K, V](send: ProducerRecord[K, V], commit: ConsumerRecord[_, _]): SendPacket[K, V] =
    SendPacket(List(send), List(commit))

  def apply[K, V](send: List[ProducerRecord[K, V]], commit: ConsumerRecord[_, _]): SendPacket[K, V] =
    SendPacket(send, List(commit))
