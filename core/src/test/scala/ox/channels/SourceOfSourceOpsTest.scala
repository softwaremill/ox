package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import java.util.concurrent.CountDownLatch
import scala.collection.mutable.ListBuffer

class SourceOfSourceOpsTest extends AnyFlatSpec with Matchers {

  "flatten" should "pipe all elements of the child sources into the output source" in {
    supervised {
      val source = Source.fromValues(
        Source.fromValues(10),
        Source.fromValues(20, 30),
        Source.fromValues(40, 50, 60)
      )
      source.flatten.toList should contain theSameElementsAs List(10, 20, 30, 40, 50, 60)
    }
  }

  it should "handle empty source" in {
    supervised {
      val source = Source.empty[Source[Int]]
      source.flatten.toList should contain theSameElementsAs Nil
    }
  }

  it should "handle singleton source" in {
    supervised {
      val source = Source.fromValues(Source.fromValues(10))
      source.flatten.toList should contain theSameElementsAs List(10)
    }
  }

  it should "pipe elements realtime" in {
    supervised {
      val source = Channel.bufferedDefault[Source[Int]]
      val lockA = CountDownLatch(1)
      val lockB = CountDownLatch(1)
      source.send(Source.fromValues(10))
      source.send {
        val subSource = Channel.bufferedDefault[Int]
        subSource.send(20)
        forkUnsupervised {
          lockA.await() // 30 won't be added until, lockA is released after 20 consumption
          subSource.send(30)
          subSource.done()
        }
        subSource
      }
      forkUnsupervised {
        lockB.await() // 40 won't be added until, lockB is released after 30 consumption
        source.send(Source.fromValues(40))
        source.done()
      }

      val collected = ListBuffer[Int]()
      source.flatten.foreachOrError { e =>
        collected += e
        if e == 20 then lockA.countDown()
        else if e == 30 then lockB.countDown()
      }
      collected should contain theSameElementsAs List(10, 20, 30, 40)
    }
  }

  it should "propagate error of any of the child sources and stop piping" in {
    val error = new Exception("intentional failure")
    supervised {
      val child1 = Channel.rendezvous[Int]
      val lock = CountDownLatch(1)
      val child1Producer = fork {
        child1.send(10)
        // wait for child2 to emit an error
        lock.await()
        // `flatten` will not receive this, as it will be short-circuited by the error
        child1.sendOrClosed(30)

      }
      val child2 = Channel.rendezvous[Int]
      fork {
        child2.send(20)
        child2.error(error)
        lock.countDown()
      }
      val source = Source.fromValues(child1, child2)

      val (collectedElems, collectedError) = source.flatten.toPartialList()
      collectedError shouldBe Some(error)
      collectedElems should contain theSameElementsAs List(10, 20)
      child1.receive() shouldBe 30
    }
  }

  it should "propagate error of the parent source and stop piping" in {
    val error = new Exception("intentional failure")
    supervised {
      val child1 = Channel.rendezvous[Int]
      val lock = CountDownLatch(1)
      fork {
        child1.send(10)
        lock.countDown()
        // depending on how quick it picks up the error from the parent
        // `flatten` may or may not receive this
        child1.send(20)
        child1.done()
      }
      val source = Channel.rendezvous[Source[Int]]
      fork {
        source.send(child1)
        // make sure the first element of child1 is consumed before emitting error
        lock.await()
        source.error(error)
      }

      val (collectedElems, collectedError) = source.flatten.toPartialList()
      collectedError shouldBe Some(error)
      collectedElems should contain atLeastOneElementOf List(10, 20)
    }
  }

  it should "stop pulling from the sources when the receiver is closed" in {
    // TODO: implement this test
  }

  extension [T](source: Source[T]) {
    def toPartialList(cb: T | Throwable => Unit = (_: Any) => ()): (List[T], Option[Throwable]) = {
      val elementCapture = ListBuffer[T]()
      var errorCapture = Option.empty[Throwable]
      try {
        for (t <- source) {
          cb(t)
          elementCapture += t
        }
      } catch {
        case ChannelClosedException.Error(e) =>
          cb(e)
          errorCapture = Some(e)
      }
      (elementCapture.toList, errorCapture)
    }
  }
}
