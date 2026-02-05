package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecords, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

import scala.jdk.CollectionConverters.*

trait KafkaConsumerWrapper[K, V]:
  def subscribe(topics: Seq[String]): Unit
  def poll(): ConsumerRecords[K, V]
  def commit(offsets: Map[TopicPartition, Long]): Unit

object KafkaConsumerWrapper:
  private val logger = LoggerFactory.getLogger(classOf[KafkaConsumerWrapper.type])

  /** Create a thread-safe wrapper on top of a [[KafkaConsumer]], which serializes calls using an actor. The actor is created within the
    * current concurrency scope and will be closed when the scope ends if the `closeWhenComplete` flag is `true`.
    */
  def apply[K, V](consumer: KafkaConsumer[K, V], closeWhenComplete: Boolean)(using Ox): ActorRef[KafkaConsumerWrapper[K, V]] =
    val logic = new KafkaConsumerWrapper[K, V]:
      override def subscribe(topics: Seq[String]): Unit =
        try consumer.subscribe(topics.asJava)
        catch
          case t: Throwable =>
            logger.error(s"Exception when subscribing to $topics", t)
            throw t

      override def poll(): ConsumerRecords[K, V] =
        try consumer.poll(java.time.Duration.ofMillis(100))
        catch
          case t: Throwable =>
            logger.error("Exception when polling for records in Kafka", t)
            throw t

      override def commit(offsets: Map[TopicPartition, Long]): Unit =
        try consumer.commitSync(offsets.view.mapValues(o => new OffsetAndMetadata(o + 1)).toMap.asJava)
        catch
          case t: Throwable =>
            logger.error("Exception when committing offsets", t)
            throw t

    def close(wrapper: KafkaConsumerWrapper[K, V]): Unit = if closeWhenComplete then
      logger.debug("Closing the Kafka consumer")
      consumer.close()

    Actor.create(logic, Some(close))
  end apply
end KafkaConsumerWrapper
