package ox.supervise

import org.slf4j.LoggerFactory
import ox.Ox.{forever, forkSupervised, scoped, uninterruptible}

import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue}
import scala.annotation.tailrec

object Broadcast {
  private val logger = LoggerFactory.getLogger(this.getClass)

  sealed trait BroadcastMessage
  case class Subscribe(consumer: String => Unit) extends BroadcastMessage
  case class Received(msg: String) extends BroadcastMessage

  case class BroadcastResult(inbox: BlockingQueue[BroadcastMessage], cancel: () => Unit)

  def broadcast[T](connector: QueueConnector)(f: BroadcastResult => T): T = scoped {
    @tailrec
    def processMessages(inbox: BlockingQueue[BroadcastMessage], consumers: Set[String => Unit]): Unit =
      inbox.take match
        case Subscribe(consumer) => processMessages(inbox, consumers + consumer)
        case Received(msg) =>
          consumers.map(consumer => forkSupervised(consumer(msg)))
          processMessages(inbox, consumers)

    def consumeForever(inbox: BlockingQueue[BroadcastMessage]): Unit = forever {
      try
        consume(connector, inbox)
        logger.info("[broadcast] queue consumer completed, restarting")
      catch
        case e: InterruptedException => throw e
        case e                       => logger.info("[broadcast] exception in queue consumer, restarting", e)
    }

    val inbox = new ArrayBlockingQueue[BroadcastMessage](32)
    val f1 = forkSupervised(consumeForever(inbox))
    val f2 = forkSupervised(processMessages(inbox, Set()))
    f(BroadcastResult(inbox, () => { f1.cancel(); f2.cancel() }))
  }

  def consume(connector: QueueConnector, inbox: BlockingQueue[BroadcastMessage]): Unit = {
    val connect: RemoteQueue =
      logger.info("[queue-start] connecting")
      val q = connector.connect
      logger.info("[queue-start] connected")
      q

    def consumeQueue(queue: RemoteQueue): Nothing = forever {
      logger.info("[queue] receiving message")
      val msg = queue.read()
      inbox.put(Received(msg))
    }

    def releaseQueue(queue: RemoteQueue): Unit =
      try
        logger.info("[queue-stop] closing")
        queue.close()
        logger.info("[queue-stop] closed")
      catch case e => logger.info("[queue-stop] exception while closing", e)

    val q = connect
    try consumeQueue(q)
    finally uninterruptible(releaseQueue(q))
  }
}
