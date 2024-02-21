package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.util.concurrent.atomic.AtomicInteger

import scala.util.{Failure, Try}

class SupervisedTest extends AnyFlatSpec with Matchers {
  "supervised" should "wait until all forks complete" in {
    val trail = Trail()

    val result = supervised {
      forkUser {
        Thread.sleep(200)
        trail.add("a")
      }

      forkUser {
        Thread.sleep(100)
        trail.add("b")
      }

      2
    }

    result shouldBe 2
    trail.add("done")
    trail.get shouldBe Vector("b", "a", "done")
  }

  it should "only wait until user forks complete" in {
    val trail = Trail()

    val result = supervised {
      fork {
        Thread.sleep(200)
        trail.add("a")
      }

      forkUser {
        Thread.sleep(100)
        trail.add("b")
      }

      2
    }

    result shouldBe 2
    trail.add("done")
    trail.get shouldBe Vector("b", "done")
  }

  it should "interrupt once any fork ends with an exception" in {
    val trail = Trail()

    val result = Try(supervised {
      forkUser {
        Thread.sleep(300)
        trail.add("a")
      }

      forkUser {
        Thread.sleep(200)
        throw new RuntimeException("x")
      }

      forkUser {
        Thread.sleep(100)
        trail.add("b")
      }

      2
    })

    result should matchPattern { case Failure(e) if e.getMessage == "x" => }
    trail.add("done")
    trail.get shouldBe Vector("b", "done")
  }

  it should "interrupt main body once a fork ends with an exception" in {
    val trail = Trail()

    val result = Try(supervised {
      forkUser {
        Thread.sleep(200)
        throw new RuntimeException("x")
      }

      Thread.sleep(300)
      trail.add("a")
    })

    result should matchPattern { case Failure(e) if e.getMessage == "x" => }
    trail.add("done")
    trail.get shouldBe Vector("done")
  }

  it should "not interrupt if an unsupervised fork ends with an exception" in {
    val trail = Trail()

    val result = supervised {
      forkUser {
        Thread.sleep(300)
        trail.add("a")
      }

      forkUnsupervised {
        Thread.sleep(200)
        throw new RuntimeException("x")
      }

      forkUser {
        Thread.sleep(100)
        trail.add("b")
      }

      2
    }

    result shouldBe 2
    trail.add("done")
    trail.get shouldBe Vector("b", "a", "done")
  }
}
