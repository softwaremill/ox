package ox.kafka

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import ox.*
import ox.channels.*

import scala.collection.mutable
import scala.concurrent.duration.*

private[kafka] def doCommit(packets: Source[SendPacket[_, _]])(using Ox) =
  val commitInterval = 1.second
  val ticks = Source.tick(commitInterval)
  val toCommit = mutable.Map[TopicPartition, Long]()
  var consumer: Sink[KafkaConsumerRequest[_, _]] = null // assuming all packets come from the same consumer

  forever {
    select(ticks, packets).orThrow match
      case () =>
        if consumer != null && toCommit.nonEmpty then
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

case class SendPacket[K, V](send: List[ProducerRecord[K, V]], commit: List[ReceivedMessage[_, _]])

object SendPacket:
  def apply[K, V](send: ProducerRecord[K, V], commit: ReceivedMessage[_, _]): SendPacket[K, V] =
    SendPacket(List(send), List(commit))

  def apply[K, V](send: List[ProducerRecord[K, V]], commit: ReceivedMessage[_, _]): SendPacket[K, V] =
    SendPacket(send, List(commit))
