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
        def computation(withException: Option[String]): Int = {
          withException match
            case None => 1
            case Some(value) =>
              throw new Exception(value)
        }

        val fork1 = fork:
          computation(withException = None)
        val fork2 = fork:
          computation(withException = Some("Oh no!"))
        val fork3 = fork:
          computation(withException = Some("Oh well.."))

        fork1.joinEither() // 1
        fork2.joinEither() // 2
        fork3.joinEither() // 3
      }
    }

    e.getMessage should startWith("Oh")
  }

}
