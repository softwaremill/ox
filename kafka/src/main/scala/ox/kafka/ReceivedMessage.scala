package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer}
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.record.TimestampType
import ox.channels.Sink

import scala.jdk.CollectionConverters.*

case class ReceivedMessage[K, V](consumer: Sink[KafkaConsumerRequest[K, V]], consumerRecord: ConsumerRecord[K, V]):
  def key: K = consumerRecord.key()
  def value: V = consumerRecord.value()
  def header: Iterable[Header] = consumerRecord.headers().asScala
  def offset: Long = consumerRecord.offset()
  def partition: Int = consumerRecord.partition()
  def topic: String = consumerRecord.topic()
  def timestamp: Long = consumerRecord.timestamp()
  def timestampType: TimestampType = consumerRecord.timestampType()
