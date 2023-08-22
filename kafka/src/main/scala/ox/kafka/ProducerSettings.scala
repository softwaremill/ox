package ox.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.{Serializer, StringSerializer}

import java.util.Properties

case class ProducerSettings[K, V](
    bootstrapServers: List[String],
    keySerializer: Serializer[K],
    valueSerializer: Serializer[V],
    otherProperties: Map[String, String]
):
  def bootstrapServers(servers: String*): ProducerSettings[K, V] = copy(bootstrapServers = servers.toList)
  def keySerializer[KK](serializer: Serializer[KK]): ProducerSettings[KK, V] = copy(keySerializer = serializer)
  def valueSerializer[VV](serializer: Serializer[VV]): ProducerSettings[K, VV] = copy(valueSerializer = serializer)
  def property(key: String, value: String): ProducerSettings[K, V] = copy(otherProperties = otherProperties + (key -> value))

  def toProperties: Properties =
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.mkString(","))
    otherProperties.foreach { case (key, value) => props.put(key, value) }
    props

object ProducerSettings:
  private val StringSerializerInstance = new StringSerializer
  def default: ProducerSettings[String, String] =
    ProducerSettings(DefaultBootstrapServers, StringSerializerInstance, StringSerializerInstance, Map.empty)
