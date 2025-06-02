package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import scala.concurrent.duration.*

class ForkTest extends AnyFlatSpec with Matchers:
  "fork" should "run two forks concurrently" in {
    val trail = Trail()
    unsupervised {
      val f1 = forkUnsupervised {
        sleep(500.millis)
        trail.add("f1 complete")
        5
      }
      val f2 = forkUnsupervised {
        sleep(1.second)
        trail.add("f2 complete")
        6
      }
      trail.add("main mid")
      trail.add(s"result = ${f1.join() + f2.join()}")
    }

    trail.get shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "allow nested forks" in {
    val trail = Trail()
    unsupervised {
      val f1 = forkUnsupervised {
        val f2 = forkUnsupervised {
          try 6
          finally trail.add("f2 complete")
        }

        try 5 + f2.join()
        finally trail.add("f1 complete")
      }

      trail.add(s"result = ${f1.join()}")
    }

    trail.get shouldBe Vector("f2 complete", "f1 complete", "result = 11")
  }

  it should "interrupt child forks when parents complete" in {
    val trail = Trail()
    unsupervised {
      val f1 = forkUnsupervised {
        forkUnsupervised {
          try
            sleep(1.second)
            trail.add("f2 complete")
            6
          catch
            case e: InterruptedException =>
              trail.add("f2 interrupted")
              throw e
        }.discard

        sleep(500.millis)
        trail.add("f1 complete")
        5
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.get shouldBe Vector("main mid", "f1 complete", "result = 5", "f2 interrupted")
  }

  it should "allow starting forks within a forkCancellable body, using the outer scope" in {
    val trail = Trail()

    supervised {
      forkCancellable {
        fork {
          trail.add("in fork")
        }
      }.discard

      sleep(100.millis)
      trail.get shouldBe Vector("in fork")
    }
  }

  it should "allow starting forks in outer scope, from an inner scope" in {
    val trail = Trail()

    supervised { ox ?=>
      supervised {
        fork {
          trail.add("in fork")
        }(using ox).join()
      }
    }

    trail.get shouldBe Vector("in fork")
  }

  it should "not allow starting forks from a thread created not by the scope" in {
    val trail = Trail()

    supervised {
      Thread
        .startVirtualThread { () =>
          try forkDiscard(trail.add("in fork"))
          catch case e: Exception => trail.add(e.getClass().getSimpleName())
        }
        .join()
    }

    trail.get shouldBe Vector("IllegalStateException")
  }
end ForkTest
