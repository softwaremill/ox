package ox.resilience

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import ox.*
import ox.util.ElapsedTime
import org.scalatest.OptionValues

class BulkheadTest extends AnyFlatSpec with Matchers with OptionValues with ElapsedTime:

  behavior of "Bulkhead operation run"

  it should "drop operation above maxConcurrentCalls" in {
    val bulkHead = Bulkhead(2)
    def f() =
      sleep(1000.millis)
      "result"

    var result1: Option[String] = None
    var result2: Option[String] = None
    var result3: Option[String] = None
    supervised:
      forkUserDiscard:
        result1 = bulkHead.runOrDrop(f())
      forkUserDiscard:
        result2 = bulkHead.runOrDrop(f())
      forkUserDiscard:
        sleep(500.millis)
        result3 = bulkHead.runOrDrop(f())

    result1 shouldBe Some("result")
    result2 shouldBe Some("result")
    result3 shouldBe None
  }

  it should "forward failure to user in case of failure" in {
    val bulkHead = Bulkhead(2)
    var counter = 0
    def f() =
      sleep(1000.millis)
      if counter > 0 then throw new RuntimeException("boom")
      counter += 1
      "result"

    var result1: Option[String] = None
    var result2: Option[Exception] = None
    var result3: Option[String] = None

    supervised:
      forkUserDiscard:
        result1 = bulkHead.runOrDrop(f())
      forkUserDiscard:
        sleep(200.millis)
        result2 = Some(the[RuntimeException] thrownBy bulkHead.runOrDrop(f()))
      forkUserDiscard:
        sleep(400.millis)
        result3 = bulkHead.runOrDrop(f())

    result1 shouldBe Some("result")
    result2.value.getMessage shouldBe "boom"
    result3 shouldBe None
  }

  behavior of "Bulkhead operation timeout"

  it should "block until acquisition is possible or timeout passes" in {
    val bulkHead = Bulkhead(1)
    def f() =
      sleep(1000.millis)
      "result"

    var duration: Option[Duration] = None
    var result1: Option[String] = None
    var result2: Option[String] = None
    supervised:
      forkUserDiscard:
        result1 = bulkHead.runOrDrop(f())
      forkUserDiscard:
        sleep(500.millis)
        val (res, dur) = measure(bulkHead.runOrDropWithTimeout(2000.millis)(f()))
        result2 = res
        duration = Some(dur)

    result1 shouldBe Some("result")
    duration.value.toMillis should be >= 1450L // Waiting for result1 to finish plus task time (minus 50 millis for tolerance)
    result2 shouldBe Some("result")
  }

  it should "respect timeout" in {
    val bulkHead = Bulkhead(1)
    def f() =
      sleep(1000.millis)
      "result"

    var duration: Option[Duration] = None
    var result1: Option[String] = None
    var result2: Option[String] = None
    supervised:
      forkUserDiscard:
        result1 = bulkHead.runOrDrop(f())
      forkUserDiscard:
        sleep(300.millis)
        val (res, dur) = measure(bulkHead.runOrDropWithTimeout(500.millis)(f()))
        result2 = res
        duration = Some(dur)

    duration.value.toMillis should be >= 450L // 50 millis less for tolerance
    result2 shouldBe None
  }
end BulkheadTest
