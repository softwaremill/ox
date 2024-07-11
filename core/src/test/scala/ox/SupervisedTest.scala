package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import scala.concurrent.duration.*
import scala.util.{Failure, Try}

class SupervisedTest extends AnyFlatSpec with Matchers {
  "supervised" should "wait until all forks complete" in {
    val trail = Trail()

    val result = supervised {
      forkUser {
        sleep(200.millis)
        trail.add("a")
      }

      forkUser {
        sleep(100.millis)
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
        sleep(200.millis)
        trail.add("a")
      }

      forkUser {
        sleep(100.millis)
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
        sleep(300.millis)
        trail.add("a")
      }

      forkUser {
        sleep(200.millis)
        throw new RuntimeException("x")
      }

      forkUser {
        sleep(100.millis)
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
        sleep(200.millis)
        throw new RuntimeException("x")
      }

      sleep(300.millis)
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
        sleep(300.millis)
        trail.add("a")
      }

      forkUnsupervised {
        sleep(200.millis)
        throw new RuntimeException("x")
      }

      forkUser {
        sleep(100.millis)
        trail.add("b")
      }

      2
    }

    result shouldBe 2
    trail.add("done")
    trail.get shouldBe Vector("b", "a", "done")
  }

  it should "handle interruption of multiple forks with `joinEither` correctly" in {
    val e = intercept[Exception] {
      supervised {
        // first, starting a fork which will sleep in the background, and which is unsupervised, so that we can .joinEither()
        val f1 = forkUnsupervised:
          sleep(1.second)
          10

        // forking a supervised fork, which throws an exception and causes the scope to end
        val f2 = fork:
          throw new Exception("oh no!")

        // this joinEither() might be interrupted because the scope ends, or it might obtain the interrupted exception,
        // because `f` is interrupted as well
        f1.joinEither()

        // either the previous operation should throw an IE, or the thread should become interrupted while joining
        f2.join()

        // getting here means that we managed to catch the IE for the main body
        fail("scope body should be interrupted")
      }
    }

    // the exception that caused the scope to end should be re-thrown
    e.getMessage shouldBe "oh no!"
  }

}
