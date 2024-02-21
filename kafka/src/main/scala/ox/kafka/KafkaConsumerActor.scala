package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecords, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object KafkaConsumerActor:
  private val logger = LoggerFactory.getLogger(classOf[KafkaConsumerActor.type])

  def apply[K, V](consumer: KafkaConsumer[K, V], closeWhenComplete: Boolean)(using Ox): Sink[KafkaConsumerRequest[K, V]] =
    val c = Channel[KafkaConsumerRequest[K, V]]()

    fork {
      try
        repeatWhile {
          c.receive() match
            case ChannelClosed.Done =>
              logger.debug("Stopping Kafka consumer actor: upstream done")
              false
            case e: ChannelClosed.Error =>
              logger.debug(s"Stopping Kafka consumer actor: upstream closed due to an error ($e)")
              false
            case KafkaConsumerRequest.Subscribe(topics) =>
              try
                consumer.subscribe(topics.asJava)
                true
              catch
                case NonFatal(e) =>
                  logger.error(s"Exception when subscribing to $topics", e)
                  c.error(e)
                  false
            case KafkaConsumerRequest.Poll(results) =>
              try
                results.send(consumer.poll(java.time.Duration.ofMillis(100)))
                true
              catch
                case NonFatal(e) =>
                  logger.error("Exception when polling for records in Kafka", e)
                  results.error(e)
                  false
            case KafkaConsumerRequest.Commit(offsets) =>
              try
                consumer.commitSync(offsets.view.mapValues(o => new OffsetAndMetadata(o + 1)).toMap.asJava)
                true
              catch
                case NonFatal(e) =>
                  logger.error("Exception when committing offsets", e)
                  c.error(e)
                  false
        }
      finally
        if closeWhenComplete then
          logger.debug("Closing the Kafka consumer")
          uninterruptible(consumer.close())
    }

    c

enum KafkaConsumerRequest[K, V]:
  case Subscribe(topics: Seq[String])
  case Poll(results: Sink[ConsumerRecords[K, V]])
  case Commit(offsets: Map[TopicPartition, Long])
