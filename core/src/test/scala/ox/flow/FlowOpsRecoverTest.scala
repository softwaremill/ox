package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosedException

class FlowOpsRecoverTest extends AnyFlatSpec with Matchers:

  behavior of "Flow.recover"

  it should "pass through elements when upstream flow succeeds" in:
    // given
    val flow = Flow.fromValues(1, 2, 3)
    val recoveryFunction: PartialFunction[Throwable, Int] = { case _: IllegalArgumentException =>
      42
    }

    // when
    val result = flow.recover(recoveryFunction).runToList()

    // then
    result shouldBe List(1, 2, 3)

  it should "emit recovery value when upstream flow fails with handled exception" in:
    // given
    val exception = new IllegalArgumentException("test error")
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(exception))
    val recoveryFunction: PartialFunction[Throwable, Int] = { case _: IllegalArgumentException =>
      42
    }

    // when
    val result = flow.recover(recoveryFunction).runToList()

    // then
    result shouldBe List(1, 2, 42)

  it should "not emit recovery value when downstream flow fails with handled exception" in:
    // given
    val exception = new IllegalArgumentException("test error")
    val recoveryFunction: PartialFunction[Throwable, Int] = { case _: IllegalArgumentException =>
      42
    }
    val flow = Flow.fromValues(1, 2).recover(recoveryFunction).concat(Flow.failed(exception))

    // when & then
    the[IllegalArgumentException] thrownBy {
      flow.runToList()
    } should have message "test error"

  it should "propagate unhandled exceptions" in:
    // given
    val exception = new RuntimeException("unhandled error")
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(exception))
    val recoveryFunction: PartialFunction[Throwable, Int] = { case _: IllegalArgumentException =>
      42
    }

    // when & then
    val caught = the[ChannelClosedException.Error] thrownBy {
      flow.recover(recoveryFunction).runToList()
    }
    caught.getCause shouldBe an[RuntimeException]
    caught.getCause.getMessage shouldBe "unhandled error"

  it should "handle multiple exception types" in:
    // given
    val exception = new IllegalStateException("state error")
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(exception))
    val recoveryFunction: PartialFunction[Throwable, Int] = {
      case _: IllegalArgumentException => 42
      case _: IllegalStateException    => 99
      case _: NullPointerException     => 0
    }

    // when
    val result = flow.recover(recoveryFunction).runToList()

    // then
    result shouldBe List(1, 2, 99)

  it should "work with different recovery value type" in:
    // given
    val exception = new IllegalArgumentException("test error")
    val flow = Flow.fromValues("a", "b").concat(Flow.failed(exception))
    val recoveryFunction: PartialFunction[Throwable, String] = { case _: IllegalArgumentException =>
      "recovered"
    }

    // when
    val result = flow.recover(recoveryFunction).runToList()

    // then
    result shouldBe List("a", "b", "recovered")

  it should "handle exception thrown during flow processing" in:
    // given
    val flow = Flow.fromValues(1, 2, 3).map(x => if x == 3 then throw new IllegalArgumentException("map error") else x)
    val recoveryFunction: PartialFunction[Throwable, Int] = { case _: IllegalArgumentException =>
      -1
    }

    // when
    val result = flow.recover(recoveryFunction).runToList()

    // then
    result shouldBe List(1, 2, -1)

  it should "work with empty flow" in:
    // given
    val flow = Flow.empty[Int]
    val recoveryFunction: PartialFunction[Throwable, Int] = { case _: IllegalArgumentException =>
      42
    }

    // when
    val result = flow.recover(recoveryFunction).runToList()

    // then
    result shouldBe List.empty

  it should "propagate exception when partial function throws" in:
    // given
    val originalException = new IllegalArgumentException("original error")
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(originalException))
    val recoveryFunction: PartialFunction[Throwable, Int] = { case _: IllegalArgumentException =>
      throw new RuntimeException("recovery failed")
    }

    // when & then
    val caught = the[ChannelClosedException.Error] thrownBy {
      flow.recover(recoveryFunction).runToList()
    }
    caught.getCause shouldBe an[RuntimeException]
    caught.getCause.getMessage shouldBe "recovery failed"
end FlowOpsRecoverTest
