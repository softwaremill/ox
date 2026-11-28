package ox.flow.reactive

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ox.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

class FlowReactiveStreamsFromPublisherTest extends AnyFunSuite with Matchers:

  /** A publisher driven from the test thread. Ignores demand; supports a single subscriber. */
  private class ManualPublisher[T] extends Publisher[T]:
    @volatile var subscriber: Subscriber[? >: T] = null
    val cancelled = new AtomicBoolean(false)
    val subscribed = new CountDownLatch(1)

    override def subscribe(s: Subscriber[? >: T]): Unit =
      subscriber = s
      s.onSubscribe(new Subscription:
        override def request(n: Long): Unit = ()
        override def cancel(): Unit = cancelled.set(true))
      subscribed.countDown()

    def next(t: T): Unit = subscriber.onNext(t)
    def complete(): Unit = subscriber.onComplete()
  end ManualPublisher

  test("should emit elements from the publisher"):
    val publisher = new ManualPublisher[Int]
    supervised:
      val result = fork(FlowReactiveStreams.fromPublisher(publisher).runToList())
      publisher.subscribed.await()
      (1 to 3).foreach(publisher.next)
      publisher.complete()
      result.join() shouldBe List(1, 2, 3)

  test("should discard undelivered elements and cancel the subscription when the consumer stops early"):
    val publisher = new ManualPublisher[Int]
    val discarded = new ConcurrentLinkedQueue[Int]()
    supervised:
      val result = fork(FlowReactiveStreams.fromPublisher(publisher, discarded.add(_).discard).take(1).runToList())
      publisher.subscribed.await()
      (1 to 5).foreach(publisher.next)
      result.join() shouldBe List(1)
    discarded.asScala.toList.sorted shouldBe List(2, 3, 4, 5)
    publisher.cancelled.get() shouldBe true
end FlowReactiveStreamsFromPublisherTest
