package ox.flow.reactive

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.flow.Flow

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Try

class FlowFromPublisherDiscardTest extends AnyFunSuite with Matchers:
  private val boom = new RuntimeException("boom")

  private class Fixture:
    val publisher = new ManualPublisher[Int]
    val emitted = new ConcurrentLinkedQueue[Int]()
    val discarded = new ConcurrentLinkedQueue[Int]()
    val flow: Flow[Int] = Flow.fromPublisher(publisher, discarded.add(_).discard).tap(emitted.add(_).discard)

    def emittedList: List[Int] = emitted.asScala.toList
    def discardedSorted: List[Int] = discarded.asScala.toList.sorted
  end Fixture

  test("should emit buffered elements before rethrowing a publisher error"):
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
      result.join() shouldBe Failure(boom)
    f.emittedList shouldBe List(1, 2, 3)
    f.discarded shouldBe empty

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
