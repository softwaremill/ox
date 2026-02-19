package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosedException

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

class FlowOpsRecoverWithTest extends AnyFlatSpec with Matchers:

  behavior of "Flow.recoverWith"

  it should "pass through elements when upstream flow succeeds" in:
    val flow = Flow.fromValues(1, 2, 3)
    val result = flow.recoverWith { case _: RuntimeException => Flow.fromValues(99) }.runToList()
    result shouldBe List(1, 2, 3)

  it should "switch to recovery flow when upstream fails with handled exception" in:
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(new IllegalArgumentException("boom")))
    val result = flow.recoverWith { case _: IllegalArgumentException => Flow.fromValues(10, 20) }.runToList()
    result shouldBe List(1, 2, 10, 20)

  it should "propagate unhandled exceptions" in:
    val flow = Flow.fromValues(1).concat(Flow.failed(new RuntimeException("unhandled")))
    val caught = the[ChannelClosedException.Error] thrownBy {
      flow.recoverWith { case _: IllegalArgumentException => Flow.fromValues(99) }.runToList()
    }
    caught.getCause shouldBe a[RuntimeException]
    caught.getCause.getMessage shouldBe "unhandled"

  it should "switch to empty recovery flow" in:
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(new RuntimeException("fail")))
    val result = flow.recoverWith { case _: RuntimeException => Flow.empty[Int] }.runToList()
    result shouldBe List(1, 2)

  it should "propagate error from recovery flow" in:
    val flow = Flow.fromValues(1).concat(Flow.failed(new RuntimeException("original")))
    val caught = the[ChannelClosedException.Error] thrownBy {
      flow.recoverWith { case _: RuntimeException => Flow.failed(new IllegalStateException("recovery failed")) }.runToList()
    }
    caught.getCause shouldBe an[IllegalStateException]
    caught.getCause.getMessage shouldBe "recovery failed"

  it should "work with empty upstream flow" in:
    val flow = Flow.empty[Int]
    val result = flow.recoverWith { case _: RuntimeException => Flow.fromValues(99) }.runToList()
    result shouldBe List.empty

  it should "propagate error from recovery flow that emits some elements then fails" in:
    val recoveryFlow = Flow.fromValues(10, 20).concat(Flow.failed(new IllegalStateException("recovery boom")))
    val flow = Flow.fromValues(1).concat(Flow.failed(new RuntimeException("original")))
    val observed = new CopyOnWriteArrayList[Int]()
    val caught = the[ChannelClosedException.Error] thrownBy {
      flow.recoverWith { case _: RuntimeException => recoveryFlow }.tap(e => observed.add(e).discard).runToList()
    }
    caught.getCause shouldBe an[IllegalStateException]
    // upstream element 1 and at least the first recovery element are observed before the error
    observed.asScala.toList should contain(1)
    observed.asScala.toList should contain(10)

end FlowOpsRecoverWithTest
