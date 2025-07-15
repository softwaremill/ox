package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail
import scala.concurrent.duration.DurationInt
import scala.util.Success

class TimeTest extends AnyFlatSpec with Matchers:
  /*
  
  we need to know all scopes - heards - that are started with the mocked time block
  each scope needs to register the heard with the mock timer on startup, and unregister when it ends

  within a tick(), we need to wait until all threads are waiting
  if they are timed_waiting -> exception -> not supported

  when a thread sleeps -> wakeup the ticker thread
  when the main thread exits -> wakeup the ticker thread
  + wakeup periodically (e.g. each second) to verify that not all threads are timed_waiting
  
  hence: mock time starts the given block in a new thread
  the driver thread suspends until its woken up  - either to complete (when the main thread completes), or to verify thread state

   */
  it should "work" in {
    val t = Trail()
    mockTime {
      println(s"[TEST] main thread id ${Thread.currentThread().threadId}")
      t.add("start")

      supervised {
        val f = fork {
          println(s"[TEST] fork id ${Thread.currentThread().threadId}")

          sleep(1.second)
          t.add("after 1 second")
          sleep(2.seconds)
          t.add("after 2 seconds")
        }

        f.join()
        t.add("done")
        10
      }
    } { ticker =>
      t.get shouldBe Vector() // no tick yet

      println("[1]")
      ticker.tick() shouldBe TickResult.Next(1.second)
      t.get shouldBe Vector("start")

      println("[2]")
      // no time advance - no changes
      ticker.tick() shouldBe TickResult.Next(1.second)
      t.get shouldBe Vector("start")

      println("[3]")
      ticker.advanceTimeAndTick(1.second) shouldBe TickResult.Next(2.seconds)
      t.get shouldBe Vector("start", "after 1 second")

      println("[4]")
      // not enough time to really advance
      ticker.advanceTimeAndTick(500.millis) shouldBe TickResult.Next(1500.milliseconds)
      t.get shouldBe Vector("start", "after 1 second")

      println("[5]")
      ticker.advanceTimeAndTick(1500.millis) shouldBe TickResult.Result(Success(10))
      t.get shouldBe Vector("start", "after 1 second", "after 2 seconds", "done")

      // idempoten
      ticker.tick() shouldBe TickResult.Result(Success(10))
    }
  }
end TimeTest
