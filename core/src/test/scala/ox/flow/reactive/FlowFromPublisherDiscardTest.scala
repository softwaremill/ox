package ox.flow.reactive

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.BufferCapacity
import ox.channels.ChannelClosedException
import ox.flow.Flow

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Try

class FlowFromPublisherDiscardTest extends AnyFunSuite with Matchers:
  private val boom = new RuntimeException("boom")

  private class Fixture(onDiscard: Int => Unit = _ => ())(using BufferCapacity):
    val publisher = new ManualPublisher[Int]
    val emitted = new ConcurrentLinkedQueue[Int]()
    val discarded = new ConcurrentLinkedQueue[Int]()
    val flow: Flow[Int] = Flow
      .fromPublisher(
        publisher,
        t =>
          discarded.add(t).discard
          onDiscard(t)
      )
      .tap(emitted.add(_).discard)

    def emittedList: List[Int] = emitted.asScala.toList
    def discardedSorted: List[Int] = discarded.asScala.toList.sorted
  end Fixture

  test("should discard buffered elements and rethrow a publisher error"):
    val f = Fixture()
    val received = new Semaphore(0)
    val gate = new Semaphore(0)
    supervised:
      val result = forkUnsupervised:
        Try:
          f.flow.runForeach: _ =>
            received.release()
            gate.acquire()
      f.publisher.subscribed.await()
      f.publisher.next(1)
      // the consumer is blocked, so 2 and 3 stay buffered when the error arrives
      received.acquire()
      f.publisher.next(2)
      f.publisher.next(3)
      f.publisher.error(boom)
      gate.release(3)
      result.join() shouldBe Failure(ChannelClosedException.Error(boom))
    f.emittedList shouldBe List(1)
    f.discardedSorted shouldBe List(2, 3)

  test("should discard undelivered elements when the consumer throws"):
    val f = Fixture()
    supervised:
      val result = forkUnsupervised(Try(f.flow.map(x => if x == 2 then throw boom else x).runToList()))
      f.publisher.subscribed.await()
      (1 to 5).foreach(f.publisher.next)
      result.join() shouldBe Failure(boom)
    f.emittedList shouldBe List(1, 2)
    f.discardedSorted shouldBe List(3, 4, 5)
    f.publisher.cancelled.get() shouldBe true

  test("should discard undelivered elements when the consumer stops early"):
    val f = Fixture()
    supervised:
      val result = fork(f.flow.take(2).runToList())
      f.publisher.subscribed.await()
      (1 to 5).foreach(f.publisher.next)
      result.join() shouldBe List(1, 2)
    f.discardedSorted shouldBe List(3, 4, 5)
    f.publisher.cancelled.get() shouldBe true

  test("should discard undelivered elements when the consumer is interrupted"):
    val f = Fixture()
    val received = new Semaphore(0)
    val gate = new Semaphore(0)
    supervised:
      val result = forkCancellable:
        f.flow.runForeach: _ =>
          received.release()
          gate.acquire()
      f.publisher.subscribed.await()
      (1 to 5).foreach(f.publisher.next)
      received.acquire()
      result.cancel().discard
    f.emittedList shouldBe List(1)
    f.discardedSorted shouldBe List(2, 3, 4, 5)
    f.publisher.cancelled.get() shouldBe true

  test("should discard all buffered elements when onDiscard throws"):
    val boom = new RuntimeException("boom") // not shared: suppressed exceptions are added to it
    val discardBoom = new RuntimeException("discard boom")
    val f = Fixture(t => if t == 3 then throw discardBoom)
    supervised:
      val result = forkUnsupervised(Try(f.flow.map(x => if x == 2 then throw boom else x).runToList()))
      f.publisher.subscribed.await()
      (1 to 5).foreach(f.publisher.next)
      result.join() shouldBe Failure(boom)
    boom.getSuppressed.toList shouldBe List(discardBoom)
    f.discardedSorted shouldBe List(3, 4, 5)

  test("should rethrow a publisher error when onDiscard throws"):
    val discardBoom = new RuntimeException("discard boom")
    val f = Fixture(t => if t == 2 then throw discardBoom)
    val received = new Semaphore(0)
    val gate = new Semaphore(0)
    supervised:
      val result = forkUnsupervised:
        Try:
          f.flow.runForeach: _ =>
            received.release()
            gate.acquire()
      f.publisher.subscribed.await()
      f.publisher.next(1)
      received.acquire()
      f.publisher.next(2)
      f.publisher.next(3)
      f.publisher.error(boom)
      gate.release()
      val error = result.join().failed.get
      error shouldBe ChannelClosedException.Error(boom)
      error.getSuppressed.toList shouldBe List(discardBoom)
    f.discardedSorted shouldBe List(2, 3)

  test("should cancel a second subscription"):
    val f = Fixture()
    val secondCancelled = new AtomicBoolean(false)
    supervised:
      val result = fork(f.flow.runToList())
      f.publisher.subscribed.await()
      f.publisher.subscriber.onSubscribe(new Subscription:
        override def request(n: Long): Unit = ()
        override def cancel(): Unit = secondCancelled.set(true))
      f.publisher.next(1)
      f.publisher.complete()
      result.join() shouldBe List(1)
    secondCancelled.get() shouldBe true
    f.publisher.cancelled.get() shouldBe false

  test("should discard an element whose send is interrupted"):
    given BufferCapacity = BufferCapacity(1)
    val f = Fixture()
    val received = new Semaphore(0)
    supervised:
      val result = forkCancellable:
        f.flow.runForeach: _ =>
          received.release()
          never
      f.publisher.subscribed.await()
      f.publisher.next(1)
      received.acquire()
      f.publisher.next(2)
      // the publisher ignores demand: the buffer is full, so this send blocks until interrupted
      val sending = new Semaphore(0)
      val sender = forkCancellable:
        sending.release()
        f.publisher.next(3)
      sending.acquire()
      sender.cancel().discard
      f.discardedSorted shouldBe List(3)
      result.cancel().discard
    f.emittedList shouldBe List(1)
    f.discardedSorted shouldBe List(2, 3)

  test("should discard elements delivered after cancellation"):
    val f = Fixture()
    supervised:
      val result = fork(f.flow.take(1).runToList())
      f.publisher.subscribed.await()
      f.publisher.next(1)
      result.join() shouldBe List(1)
    f.publisher.next(2)
    f.discardedSorted shouldBe List(2)

  test("should cancel a subscription that arrives after the flow is cancelled"):
    val publisher = new ManualPublisher[Int](deferOnSubscribe = true)
    supervised:
      val result = forkCancellable(Flow.fromPublisher(publisher).runDrain())
      publisher.subscribed.await()
      result.cancel().discard
    publisher.sendOnSubscribe()
    publisher.cancelled.get() shouldBe true
    publisher.requested.get() shouldBe 0
end FlowFromPublisherDiscardTest
