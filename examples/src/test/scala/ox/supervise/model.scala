package ox.supervise

trait RemoteQueue:
  def read(): String
  def close(): Unit

trait QueueConnector:
  def connect: RemoteQueue
