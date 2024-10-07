package ox.kafka

import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}
import ox.kafka.ConsumerSettings.AutoOffsetReset

import java.util.Properties

case class ConsumerSettings[K, V](
    bootstrapServers: List[String],
    groupId: String,
    keyDeserializer: Deserializer[K],
    valueDeserializer: Deserializer[V],
    autoCommit: Boolean,
    autoOffsetReset: Option[AutoOffsetReset],
    otherProperties: Map[String, String]
):
  def bootstrapServers(servers: String*): ConsumerSettings[K, V] = copy(bootstrapServers = servers.toList)
  def groupId(gid: String): ConsumerSettings[K, V] = copy(groupId = gid)
  def keyDeserializer[KK](deserializer: Deserializer[KK]): ConsumerSettings[KK, V] = copy(keyDeserializer = deserializer)
  def valueDeserializer[VV](deserializer: Deserializer[VV]): ConsumerSettings[K, VV] = copy(valueDeserializer = deserializer)
  def autoOffsetReset(reset: AutoOffsetReset): ConsumerSettings[K, V] = copy(autoOffsetReset = Some(reset))
  def property(key: String, value: String): ConsumerSettings[K, V] = copy(otherProperties = otherProperties + (key -> value))

  def toProperties: Properties =
    val props = new Properties()
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit.toString)
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.mkString(","))
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    autoOffsetReset.foreach { reset => props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, reset.toString.toLowerCase) }
    otherProperties.foreach { case (key, value) => props.put(key, value) }
    props
  end toProperties

  def toConsumer: KafkaConsumer[K, V] = KafkaConsumer(toProperties, keyDeserializer, valueDeserializer)
end ConsumerSettings

object ConsumerSettings:
  private val StringDeserializerInstance = new StringDeserializer
  def default(groupId: String): ConsumerSettings[String, String] =
    ConsumerSettings(
      DefaultBootstrapServers,
      groupId,
      StringDeserializerInstance,
      StringDeserializerInstance,
      autoCommit = false,
      None,
      Map.empty
    )

  enum AutoOffsetReset:
    case Earliest, Latest, None
end ConsumerSettings
