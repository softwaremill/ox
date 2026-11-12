package ox.flow.reactive

import ox.discard

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow.Publisher
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A publisher driven from the test thread. Ignores demand; supports a single subscriber. With `deferOnSubscribe`, `onSubscribe` is only
  * called by [[sendOnSubscribe]].
  */
class ManualPublisher[T](deferOnSubscribe: Boolean = false) extends Publisher[T]:
  @volatile var subscriber: Subscriber[? >: T] = null
  val requested = new AtomicLong(0)
  val cancelled = new AtomicBoolean(false)
  val subscribed = new CountDownLatch(1)

  private val subscription = new Subscription:
    override def request(n: Long): Unit = requested.addAndGet(n).discard
    override def cancel(): Unit = cancelled.set(true)

  override def subscribe(s: Subscriber[? >: T]): Unit =
    subscriber = s
    if !deferOnSubscribe then sendOnSubscribe()
    subscribed.countDown()

  def sendOnSubscribe(): Unit = subscriber.onSubscribe(subscription)

  def next(t: T): Unit = subscriber.onNext(t)
  def error(e: Throwable): Unit = subscriber.onError(e)
  def complete(): Unit = subscriber.onComplete()

end ManualPublisher
