package ox.kafka

import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

object KafkaStage:
  private val logger = LoggerFactory.getLogger(classOf[KafkaStage.type])

  extension [K, V](source: Source[ProducerRecord[K, V]])
    def mapPublish(settings: ProducerSettings[K, V])(using StageCapacity, Ox): Source[RecordMetadata] =
      mapPublish(settings.toProducer, closeWhenComplete = true)

    def mapPublish(producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using StageCapacity, Ox): Source[RecordMetadata] =
      source.mapAsView(r => SendPacket(List(r), Nil)).mapPublishAndCommit(producer, closeWhenComplete, commitOffsets = false)

  extension [K, V](source: Source[SendPacket[K, V]])
    /** For each packet, first all messages (producer records) are sent. Then, all messages up to the offsets of the consumer messages are
      * committed. The metadata of the published records is sent downstream.
      */
    def mapPublishAndCommit(producerSettings: ProducerSettings[K, V])(using StageCapacity, Ox): Source[RecordMetadata] =
      mapPublishAndCommit(producerSettings.toProducer, closeWhenComplete = true)

    /** For each packet, first all messages (producer records) are sent. Then, all messages up to the offsets of the consumer messages are
      * committed. The metadata of the published records is sent downstream.
      *
      * @param producer
      *   The producer that is used to send messages.
      */
    def mapPublishAndCommit(producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using StageCapacity, Ox): Source[RecordMetadata] =
      mapPublishAndCommit(producer, closeWhenComplete, commitOffsets = true)

    private def mapPublishAndCommit(producer: KafkaProducer[K, V], closeWhenComplete: Boolean, commitOffsets: Boolean)(using
        StageCapacity,
        Ox
    ): Source[RecordMetadata] =
      val c = StageCapacity.newChannel[RecordMetadata]
      val exceptions = Channel.unlimited[Exception]
      val metadata = Channel[(Long, RecordMetadata)](128)
      val toCommit = Channel[SendPacket[_, _]](128)

      val sendInSequence = SendInSequence(c)

      forkDaemon {
        try
          // starting a nested scope, so that the committer is interrupted when the main process ends
          scoped {
            // committer
            if commitOffsets then fork(tapException(doCommit(toCommit))(c.error))

            repeatWhile {
              select(exceptions.receiveClause, metadata.receiveClause, source.receiveOrDoneClause) match
                case ChannelClosed.Error(r)    => c.error(r); false
                case ChannelClosed.Done        => sendInSequence.drainFromThenDone(exceptions, metadata); false
                case exceptions.Received(e)    => c.error(e); false
                case metadata.Received((s, m)) => sendInSequence.send(s, m); true
                case source.Received(packet) =>
                  try
                    sendPacket(producer, packet, sendInSequence, toCommit, exceptions, metadata)
                    true
                  catch
                    case e: Exception =>
                      c.error(e)
                      false
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
      sendInSequence: SendInSequence[RecordMetadata],
      toCommit: Sink[SendPacket[_, _]],
      exceptions: Sink[Exception],
      metadata: Sink[(Long, RecordMetadata)]
  ): Unit =
    val leftToSend = new AtomicInteger(packet.send.size)
    packet.send.foreach { toSend =>
      val sequenceNo = sendInSequence.nextSequenceNo
      producer.send(
        toSend,
        (m: RecordMetadata, e: Exception) =>
          if e != null then exceptions.send(e)
          else {
            metadata.send((sequenceNo, m))
            if leftToSend.decrementAndGet() == 0 then toCommit.send(packet)
          }
      )
    }

  /** Sends `T` elements to the given `c` sink, when elements with subsequence sequence numbers are available. */
  private class SendInSequence[T](c: Sink[T]):
    private var sequenceNoNext = 0L
    private var sequenceNoToSendNext = 0L
    private val toSend = mutable.SortedSet[(Long, T)]()(Ordering.by(_._1))

    def nextSequenceNo: Long =
      val n = sequenceNoNext
      sequenceNoNext += 1
      n

    def send(sequenceNo: Long, v: T): Unit =
      toSend.add((sequenceNo, v))
      trySend()

    def allSent: Boolean = sequenceNoNext == sequenceNoToSendNext

    @tailrec
    private def trySend(): Unit = toSend.headOption match
      case Some((s, m)) if s == sequenceNoToSendNext =>
        toSend.remove((s, m))
        c.send(m)
        sequenceNoToSendNext += 1
        trySend()
      case _ => ()

    @tailrec
    final def drainFromThenDone(
        exceptions: Source[Exception],
        incoming: Source[(Long, T)]
    ): Unit =
      if allSent then c.done()
      else
        select(exceptions.receiveClause, incoming.receiveClause) match
          case ChannelClosed.Error(r)    => c.error(r)
          case ChannelClosed.Done        => throw new IllegalStateException()
          case exceptions.Received(e)    => c.error(e)
          case incoming.Received((s, m)) => send(s, m); drainFromThenDone(exceptions, incoming)
