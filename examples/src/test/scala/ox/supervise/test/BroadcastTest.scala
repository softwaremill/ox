package ox.supervise.test

import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.{discard, sleep}
import ox.supervise.{Broadcast, QueueConnector, RemoteQueue}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class BroadcastTest extends AnyFlatSpec with Matchers with ScalaFutures with IntegrationPatience with Eventually {

  it should "forward messages and recover from failures" in {
    val testData = createTestData

    val receivedMessages = new ConcurrentLinkedQueue[String]()

    Broadcast.broadcast(testData.queueConnector) { br =>
      try
        br.inbox.put(Broadcast.Subscribe(msg => receivedMessages.add(msg).discard))

        eventually {
          receivedMessages.asScala.toList.slice(0, 5) should be(List("msg1", "msg2", "msg3", "msg", "msg"))

          testData.connectingWhileClosing.get() should be(false)
          testData.connectingWithoutClosing.get() should be(false)
        }
      finally br.cancel()
    }
  }

  trait TestData:
    def connectingWhileClosing: AtomicBoolean
    def connectingWithoutClosing: AtomicBoolean
    def queue1: RemoteQueue
    def queue2: RemoteQueue
    def queue3: RemoteQueue
    def queueConnector: QueueConnector

  def createTestData: TestData = new TestData {
    val closing = new AtomicBoolean()
    val lastClosed = new AtomicBoolean(true)
    val connectingWhileClosing = new AtomicBoolean(false)
    val connectingWithoutClosing = new AtomicBoolean(false)

    def doClose(): Unit =
      closing.set(true)
      sleep(500.millis)
      closing.set(false)

    val queue1: RemoteQueue = new RemoteQueue {
      val counter = new AtomicInteger()

      override def read(): String =
        sleep(500.millis) // delay 1st message so that consumers can subscribe
        counter.incrementAndGet() match
          case 1 => "msg1"
          case _ => throw new RuntimeException("exception 1")

      override def close(): Unit = doClose()
    }
    val queue2: RemoteQueue = new RemoteQueue {
      val counter = new AtomicInteger()

      override def read(): String =
        sleep(100.millis)
        counter.incrementAndGet() match
          case 1 => "msg2"
          case 2 => "msg3"
          case _ => throw new RuntimeException("exception 2")

      override def close(): Unit = doClose()
    }
    val queue3: RemoteQueue = new RemoteQueue {
      override def read(): String =
        sleep(100.millis)
        "msg"

      override def close(): Unit = doClose()
    }

    val queueConnector: QueueConnector = new QueueConnector {
      val counter = new AtomicInteger()

      override def connect: RemoteQueue = {
        if (closing.get()) {
          connectingWhileClosing.set(true)
          println(s"Connecting while closing! Counter: ${counter.get()}")
        }
        if (!lastClosed.get()) {
          connectingWithoutClosing.set(true)
          println(s"Reconnecting without closing the previous connection! Counter: ${counter.get()}")
        }
        counter.incrementAndGet() match {
          case 1 => queue1
          case 2 => throw new RuntimeException("connect exception 1")
          case 3 => queue2
          case 4 => throw new RuntimeException("connect exception 2")
          case 5 => throw new RuntimeException("connect exception 3")
          case _ => queue3
        }
      }
    }
  }
}
