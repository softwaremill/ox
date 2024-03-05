package ox.sockets.test

import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.raceResult
import ox.sockets.{ConnectedSocket, Router, Socket, SocketTerminatedException}

import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedQueue, TimeUnit}
import scala.jdk.CollectionConverters.*

class RouterTest extends AnyFlatSpec with Matchers with Eventually with IntegrationPatience {

  it should "distribute message and connect new clients" in {
    val acceptQueue = new ArrayBlockingQueue[ConnectedSocket](1024)

    val testSocket = new Socket:
      override def accept(timeout: Long): ConnectedSocket =
        acceptQueue.poll(timeout, TimeUnit.MILLISECONDS)

    class TestConnectedSocket extends ConnectedSocket:
      private val receiveQueue = new ArrayBlockingQueue[String](1024)
      private val sentQueue = new ConcurrentLinkedQueue[String]()

      override def send(msg: String): Unit = sentQueue.offer(msg)
      override def receive(timeout: Long): String = {
        val msg = receiveQueue.poll(timeout, TimeUnit.MILLISECONDS)
        if msg == "KILL" then {
          throw new SocketTerminatedException
        } else msg
      }

      def sent: List[String] = sentQueue.asScala.toList
      def receiveNext(msg: String): Unit = receiveQueue.offer(msg)

    def socketListen = Router.router(testSocket)

    def socketTest =
      // create 3 clients, send message: should be broadcast
      val s1 = new TestConnectedSocket
      val s2 = new TestConnectedSocket
      val s3 = new TestConnectedSocket
      acceptQueue.put(s1)
      acceptQueue.put(s2)
      acceptQueue.put(s3)
      Thread.sleep(100) // wait for the messages to be handled

      s1.receiveNext("msg1")
      eventually {
        s1.sent should be(Nil)
        s2.sent should be(List("msg1"))
        s3.sent should be(List("msg1"))
      }

      // create more clients, send more messages
      val s4 = new TestConnectedSocket
      val s5 = new TestConnectedSocket
      acceptQueue.put(s4)
      acceptQueue.put(s5)
      Thread.sleep(100) // wait for the messages to be handled

      s4.receiveNext("msg2")
      s4.receiveNext("msg3")
      eventually {
        s1.sent should be(List("msg2", "msg3"))
        s2.sent should be(List("msg1", "msg2", "msg3"))
        s3.sent should be(List("msg1", "msg2", "msg3"))
        s4.sent should be(Nil)
        s5.sent should be(List("msg2", "msg3"))
      }

      // terminate one client, send a message
      s5.receiveNext("KILL")
      Thread.sleep(100) // wait for the message to be handled
      s1.receiveNext("msg4")
      eventually {
        s1.sent should be(List("msg2", "msg3"))
        s2.sent should be(List("msg1", "msg2", "msg3", "msg4"))
        s3.sent should be(List("msg1", "msg2", "msg3", "msg4"))
        s4.sent should be(List("msg4"))
        s5.sent should be(List("msg2", "msg3"))
      }

    raceResult(socketListen, socketTest)
  }
}
