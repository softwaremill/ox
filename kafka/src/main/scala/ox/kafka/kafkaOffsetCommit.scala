package ox.kafka

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import ox.*
import ox.channels.*

import scala.collection.mutable
import scala.concurrent.duration.*
import ox.flow.Flow

/** Common trait for packets that contain messages to commit. */
trait HasCommit:
  def commit: List[ReceivedMessage[_, _]]

private[kafka] def doCommit[K, V](consumer: ActorRef[KafkaConsumerWrapper[K, V]], packets: Source[HasCommit]): Unit =
  val commitInterval = 1.second
  val toCommit = mutable.Map[TopicPartition, Long]()

  def commitAll(): Unit = if consumer != null && toCommit.nonEmpty then
    // waiting for the commit to happen
    consumer.ask(_.commit(toCommit.toMap))
    toCommit.clear()

  // the flow should finish once the `packets` source is done
  Flow.tick(commitInterval).merge(Flow.fromSource(packets), propagateDoneRight = true).runForeach {
    case ()                => commitAll()
    case packet: HasCommit =>
      packet.commit.foreach { receivedMessage =>
        val tp = new TopicPartition(receivedMessage.topic, receivedMessage.partition)
        toCommit.updateWith(tp) {
          case Some(offset) => Some(math.max(offset, receivedMessage.offset))
          case None         => Some(receivedMessage.offset)
        }
      }
  }

  // once done, commit any remaining offsets
  commitAll()
end doCommit

case class SendPacket[K, V](send: List[ProducerRecord[K, V]], commit: List[ReceivedMessage[_, _]]) extends HasCommit

object SendPacket:
  def apply[K, V](send: ProducerRecord[K, V], commit: ReceivedMessage[?, ?]): SendPacket[K, V] =
    SendPacket(List(send), List(commit))

  def apply[K, V](send: List[ProducerRecord[K, V]], commit: ReceivedMessage[?, ?]): SendPacket[K, V] =
    SendPacket(send, List(commit))

/** A packet containing only commit messages (consumer records) to be committed. */
case class CommitPacket(commit: List[ReceivedMessage[_, _]]) extends HasCommit

object CommitPacket:
  def apply(commit: ReceivedMessage[?, ?]): CommitPacket =
    CommitPacket(List(commit))
