package ox.channels

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import java.util.concurrent.CountDownLatch
import scala.collection.mutable.ListBuffer

class SourceOpsFlattenTest extends AnyFlatSpec with Matchers with OptionValues {

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

  it should "not flatten nested sources" in {
    supervised {
      val source = Source.fromValues(Source.fromValues(Source.fromValues(10)))
      source.flatten.toList.map(_.toList) should contain theSameElementsAs List(List(10))
    }
  }

  it should "handle subsequent flatten calls" in {
    supervised {
      val source = Source.fromValues(Source.fromValues(Source.fromValues(10), Source.fromValues(20)))
      source.flatten.flatten.toList should contain theSameElementsAs List(10, 20)
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
    supervised {
      val child1 = Channel.rendezvous[Int]
      val lock = CountDownLatch(1)
      fork {
        child1.send(10)
        lock.await() // wait for child2 to emit an error
        child1.send(30) // `flatten` will not receive this, as it will be short-circuited by the error
        child1.doneOrClosed()
      }
      val child2 = Channel.rendezvous[Int]
      fork {
        child2.send(20)
        child2.error(new Exception("intentional failure"))
        lock.countDown()
      }
      val source = Source.fromValues(child1, child2)
      val flattenSource = {
        implicit val capacity: StageCapacity = StageCapacity(0)
        source.flatten
      }
      Set(flattenSource.receive(), flattenSource.receive()) shouldBe Set(10, 20)
      flattenSource.receiveOrClosed() should be(a[ChannelClosed.Error])
      child1.receive() shouldBe 30
      child1.receiveOrClosed() shouldBe ChannelClosed.Done
    }
  }

  it should "propagate error of the parent source and stop piping" in {
    supervised {
      val child1 = Channel.rendezvous[Int]
      val lockA = CountDownLatch(1)
      val lockB = CountDownLatch(1)
      fork {
        child1.send(10)
        lockA.countDown()
        lockB.await() // make sure parent source is closed with an error
        child1.send(20) // `flatten` will not receive this, as it will be short-circuited by the error of parent
        child1.done()
      }
      val source = Channel.rendezvous[Source[Int]]
      fork {
        source.send(child1)
        lockA.await() // make sure 10 of child1 is consumed before emitting error
        source.error(new Exception("intentional failure"))
        lockB.countDown()
      }

      val flattenSource = {
        implicit val capacity: StageCapacity = StageCapacity(0)
        source.flatten
      }
      flattenSource.receive() shouldBe 10
      flattenSource.receiveOrClosed() should be(a[ChannelClosed.Error])
      child1.receive() shouldBe 20
      child1.receiveOrClosed() shouldBe ChannelClosed.Done
    }
  }

  it should "stop pulling from the sources when the receiver is closed" in {
    val child1 = Channel.rendezvous[Int]

    Thread.startVirtualThread(() => {
      child1.send(10)
      // at this point `flatten` channel is closed
      // so although `flatten` thread receives "20" element
      // it can not push it to its output channel and it will be lost
      child1.send(20)
      child1.send(30)
      child1.done()
    })

    supervised {
      val source = Source.fromValues(child1)
      val flattenSource = {
        implicit val capacity: StageCapacity = StageCapacity(0)
        source.flatten
      }
      flattenSource.receive() shouldBe 10
    }

    child1.receiveOrClosed() shouldBe 30
    child1.receiveOrClosed() shouldBe ChannelClosed.Done
  }
}
