package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecords, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.*

import scala.jdk.CollectionConverters.*

object KafkaConsumerActor:
  private val logger = LoggerFactory.getLogger(classOf[KafkaConsumerActor.type])

  def apply[K, V](consumer: KafkaConsumer[K, V], closeWhenComplete: Boolean)(using Ox): Sink[KafkaConsumerRequest[K, V]] =
    val c = Channel.rendezvous[KafkaConsumerRequest[K, V]]

    fork {
      try
        repeatWhile {
          c.receiveSafe() match
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
                case t: Throwable =>
                  logger.error(s"Exception when subscribing to $topics", t)
                  c.errorSafe(t)
                  false
            case KafkaConsumerRequest.Poll(results) =>
              try
                results.send(consumer.poll(java.time.Duration.ofMillis(100)))
                true
              catch
                case t: Throwable =>
                  logger.error("Exception when polling for records in Kafka", t)
                  results.errorSafe(t)
                  c.errorSafe(t)
                  false
            case KafkaConsumerRequest.Commit(offsets, result) =>
              try
                consumer.commitSync(offsets.view.mapValues(o => new OffsetAndMetadata(o + 1)).toMap.asJava)
                result.sendSafe(())
                true
              catch
                case t: Throwable =>
                  logger.error("Exception when committing offsets", t)
                  result.errorSafe(t)
                  c.errorSafe(t)
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
  case Commit(offsets: Map[TopicPartition, Long], results: Sink[Unit])
