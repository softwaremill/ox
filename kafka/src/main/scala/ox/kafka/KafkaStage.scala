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
    /** Publish the messages using a producer created with the given `settings`.
      *
      * @return
      *   A stream of published records metadata, in the order in which the [[ProducerRecord]]s are received.
      */
    def mapPublish(settings: ProducerSettings[K, V])(using StageCapacity, Ox): Source[RecordMetadata] =
      mapPublish(settings.toProducer, closeWhenComplete = true)

    /** Publish the messages using the given `producer`. The producer is closed depending on the `closeWhenComplete` flag, after all
      * messages are published, or when an exception occurs.
      *
      * @return
      *   A stream of published records metadata, in the order in which the [[ProducerRecord]]s are received.
      */
    def mapPublish(producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using StageCapacity, Ox): Source[RecordMetadata] =
      source.mapAsView(r => SendPacket(List(r), Nil)).mapPublishAndCommit(producer, closeWhenComplete, commitOffsets = false)

  extension [K, V](source: Source[SendPacket[K, V]])
    /** For each packet, first all messages (producer records) from [[SendPacket.send]] are sent, using a producer created with the given
      * `producerSettings`. Then, all messages from [[SendPacket.commit]] are committed: for each topic-partition, up to the highest
      * observed offset.
      *
      * @return
      *   A stream of published records metadata, in the order in which the [[SendPacket]]s are received.
      */
    def mapPublishAndCommit(producerSettings: ProducerSettings[K, V])(using StageCapacity, Ox): Source[RecordMetadata] =
      mapPublishAndCommit(producerSettings.toProducer, closeWhenComplete = true)

    /** For each packet, first all messages (producer records) are sent, using the given `producer`. Then, all messages from
      * [[SendPacket.commit]] are committed: for each topic-partition, up to the highest observed offset.
      *
      * The producer is closed depending on the `closeWhenComplete` flag, after all messages are published, or when an exception occurs.
      *
      * @param producer
      *   The producer that is used to send messages.
      * @return
      *   A stream of published records metadata, in the order in which the [[SendPacket]]s are received.
      */
    def mapPublishAndCommit(producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using
        StageCapacity,
        Ox
    ): Source[RecordMetadata] =
      mapPublishAndCommit(producer, closeWhenComplete, commitOffsets = true)

    private def mapPublishAndCommit(producer: KafkaProducer[K, V], closeWhenComplete: Boolean, commitOffsets: Boolean)(using
        StageCapacity,
        Ox
    ): Source[RecordMetadata] =
      // source - the upstream from which packets are received

      // the result, where metadata of published records is sent in the same order, as the received packets
      val c = StageCapacity.newChannel[RecordMetadata]
      // a helper channel to signal any exceptions that occur while publishing or committing offsets
      // the exceptions & metadata channels are unlimited so as to only block the Kafka callback thread in the smallest
      // possible way
      val exceptions = Channel.unlimited[Exception]
      // possible out-of-order metadata of the records published from `packet.send`
      val metadata = Channel.unlimited[(Long, RecordMetadata)]
      // packets which are fully sent, and should be committed
      val toCommit = StageCapacity.newChannel[SendPacket[_, _]]
      // used to reorder values received from `metadata` using the assigned sequence numbers
      val sendInSequence = SendInSequence(c)

      fork {
        try
          // starting a nested scope, so that the committer is interrupted when the main process ends (when there's an exception)
          unsupervised {
            // committer
            val commitDoneSource =
              if commitOffsets then Source.fromFork(fork(doCommit(toCommit).tapException(e => c.errorOrClosed(e).discard)))
              else Source.empty

            repeatWhile {
              selectOrClosed(exceptions.receiveClause, metadata.receiveClause, source.receiveClause) match
                case ChannelClosed.Error(r) => c.errorOrClosed(r); false
                case ChannelClosed.Done     =>
                  // waiting until all records are sent and metadata forwarded to `c`
                  sendInSequence.drainFrom(metadata, exceptions)
                  // we now know that there won't be any more offsets sent to be committed - we can complete the channel
                  toCommit.done()
                  // waiting until the commit fork is done - this might also return Done if commitOffsets is false, hence the safe variant
                  commitDoneSource.receiveOrClosed()
                  // completing the downstream
                  c.done()
                  // and finally winding down this scope & fork
                  false
                case exceptions.Received(e)    => c.errorOrClosed(e); false
                case metadata.Received((s, m)) => sendInSequence.send(s, m); true
                case source.Received(packet) =>
                  try
                    sendPacket(producer, packet, sendInSequence, toCommit, exceptions, metadata, commitOffsets)
                    true
                  catch
                    case e: Exception =>
                      c.errorOrClosed(e)
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
      metadata: Sink[(Long, RecordMetadata)],
      commitOffsets: Boolean
  ): Unit =
    val leftToSend = new AtomicInteger(packet.send.size)
    packet.send.foreach { toSend =>
      val sequenceNo = sendInSequence.nextSequenceNo
      producer.send(
        toSend,
        (m: RecordMetadata, e: Exception) =>
          if e != null then exceptions.sendOrClosed(e).discard
          else {
            // sending commit request first, as when upstream `source` is done, we need to know that all commits are
            // scheduled in order to shut down properly
            if commitOffsets && leftToSend.decrementAndGet() == 0 then toCommit.send(packet)
            metadata.send((sequenceNo, m))
          }
      )
    }

/** Sends `T` elements to the given `c` sink, when elements with subsequent sequence numbers are available. Thread-unsafe. */
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
  final def drainFrom(
      incoming: Source[(Long, T)],
      exceptions: Source[Exception]
  ): Unit =
    if !allSent then
      selectOrClosed(exceptions.receiveClause, incoming.receiveClause) match
        case ChannelClosed.Error(r)    => c.errorOrClosed(r).discard
        case ChannelClosed.Done        => throw new IllegalStateException()
        case exceptions.Received(e)    => c.errorOrClosed(e).discard
        case incoming.Received((s, m)) => send(s, m); drainFrom(incoming, exceptions)
