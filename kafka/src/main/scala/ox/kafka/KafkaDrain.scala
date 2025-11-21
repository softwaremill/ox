package ox.kafka

import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

import ox.flow.Flow

object KafkaDrain:
  private val logger = LoggerFactory.getLogger(classOf[KafkaDrain.type])

  /** @return
    *   A drain, which sends all records emitted by the provided [[Flow]].
    */
  def runPublish[K, V](settings: ProducerSettings[K, V])(using BufferCapacity): Flow[ProducerRecord[K, V]] => Unit = flow =>
    runPublish(settings.toProducer, closeWhenComplete = true)(flow)

  /** @return
    *   A drain, which sends all records emitted by the provided [[Flow]].
    */
  def runPublish[K, V](producer: KafkaProducer[K, V], closeWhenComplete: Boolean)(using
      BufferCapacity
  ): Flow[ProducerRecord[K, V]] => Unit =
    flow =>
      // if sending multiple records ends in an exception, we'll receive at most one anyway; we don't want to block the
      // producers, hence creating an unbounded channel
      val producerExceptions = Channel.unlimited[Throwable]

      supervised:
        val source = flow.runToChannel()
        try
          repeatWhile {
            selectOrClosed(producerExceptions.receiveClause, source.receiveClause) match // bias on exceptions
              case e: ChannelClosed.Error         => throw e.toThrowable
              case ChannelClosed.Done             => false // source must be done, as producerExceptions is never done
              case producerExceptions.Received(e) => throw e
              case source.Received(record)        =>
                producer.send(
                  record,
                  (_: RecordMetadata, exception: Exception) =>
                    if exception != null then
                      logger.error("Exception when sending record", exception)
                      producerExceptions.sendOrClosed(exception).discard
                )
                true
          }
        finally
          if closeWhenComplete then uninterruptible(producer.close())
        end try
  end runPublish

  /** @return
    *   A drain, which consumes all packets emitted by the provided [[Flow]]. For each packet, first all `send` messages (producer records)
    *   are sent, using a producer created with the given `producerSettings`. Then, all `commit` messages (consumer records) up to their
    *   offsets are committed, using the given `consumer`.
    */
  def runPublishAndCommit[K, V](producerSettings: ProducerSettings[K, V], consumer: ActorRef[KafkaConsumerWrapper[K, V]])(using
      BufferCapacity
  ): Flow[SendPacket[K, V]] => Unit =
    flow => runPublishAndCommit(producerSettings.toProducer, consumer, closeWhenComplete = true)(flow)

  /** @param producer
    *   The producer that is used to send messages.
    * @return
    *   A drain, which consumes all packets emitted by the provided [[Flow]]. For each packet, first all `send` messages (producer records)
    *   are sent, using the given `producer`. Then, all `commit` messages (consumer records) up to their offsets are committed, using the
    *   given `consumer`.
    */
  def runPublishAndCommit[K, V](producer: KafkaProducer[K, V], consumer: ActorRef[KafkaConsumerWrapper[K, V]], closeWhenComplete: Boolean)(
      using BufferCapacity
  ): Flow[SendPacket[K, V]] => Unit = flow =>
    import KafkaStage.*
    flow.mapPublishAndCommit(producer, consumer, closeWhenComplete).runDrain()

  /** @return
    *   A drain, which consumes all commit packets emitted by the provided [[Flow]]. For each packet, all `commit` messages (consumer
    *   records) are committed: for each topic-partition, up to the highest observed offset, using the given `consumer`.
    */
  def runCommit[K, V](consumer: ActorRef[KafkaConsumerWrapper[K, V]])(using BufferCapacity): Flow[CommitPacket] => Unit = flow =>
    import KafkaStage.*
    flow.mapCommit(consumer).runDrain()
end KafkaDrain
