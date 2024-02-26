package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

class ForkTest extends AnyFlatSpec with Matchers {
  "fork" should "run two forks concurrently" in {
    val trail = Trail()
    scoped {
      val f1 = fork {
        Thread.sleep(500)
        trail.add("f1 complete")
        5
      }
      val f2 = fork {
        Thread.sleep(1000)
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
    scoped {
      val f1 = fork {
        val f2 = fork {
          try 6
          finally trail.add("f2 complete")
        }

        try 5 + f2.join()
        finally trail.add("f1 complete")
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.get shouldBe Vector("main mid", "f2 complete", "f1 complete", "result = 11")
  }

  it should "allow extension method syntax" in {
    import ox.syntax.*
    val trail = Trail()
    scoped {
      val f1 = {
        val f2 = {
          try 6
          finally trail.add("f2 complete")
        }.fork

        try 5 + f2.join()
        finally trail.add("f1 complete")
      }.fork

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.get shouldBe Vector("main mid", "f2 complete", "f1 complete", "result = 11")
  }

  it should "interrupt child forks when parents complete" in {
    val trail = Trail()
    scoped {
      val f1 = fork {
        fork {
          try
            Thread.sleep(1000)
            trail.add("f2 complete")
            6
          catch
            case e: InterruptedException =>
              trail.add("f2 interrupted")
              throw e
        }

        Thread.sleep(500)
        trail.add("f1 complete")
        5
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.get shouldBe Vector("main mid", "f1 complete", "result = 5", "f2 interrupted")
  }
}
