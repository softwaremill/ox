package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import java.util.concurrent.atomic.AtomicBoolean

class ActorTest extends AnyFlatSpec with Matchers:

  trait Test1 {
    def f(x: Int): Long
  }

  it should "invoke methods on the actor" in supervised {
    var state = 0L
    val logic = new Test1 {
      override def f(x: Int): Long =
        state += x
        state
    }

    val ref = Actor.create(logic)

    ref.ask(_.f(10)) shouldBe 10
    ref.ask(_.f(20)) shouldBe 30
  }

  it should "protect the internal state of the actor" in supervised {
    var state = 0L
    val logic = new Test1 {
      override def f(x: Int): Long =
        state += x
        state
    }

    val ref = Actor.create(logic)

    val outer = 1000
    val inner = 1000

    val forks = for (i <- 1 to outer) yield fork {
      for (j <- 1 to inner) {
        ref.ask(_.f(1))
      }
    }
    forks.foreach(_.join())

    ref.ask(_.f(0)) shouldBe outer.toLong * inner
  }

  it should "run the close callback before re-throwing the exception" in {
    val isClosed = new AtomicBoolean(false)
    val thrown = the[RuntimeException] thrownBy {
      supervised {
        var state = 0L
        val logic = new Test1 {
          override def f(x: Int): Long =
            state += x
            if state > 2 then throw new RuntimeException("too much")
            state
        }

        val ref = Actor.create(logic, Some(_ => isClosed.set(true)))

        ref.ask(_.f(5))
      }
    }

    thrown.getMessage shouldBe "too much"
    isClosed.get() shouldBe true
  }

  it should "end the scope when an exception is thrown when handling .tell" in {
    val thrown = the[RuntimeException] thrownBy {
      supervised {
        val logic = new Test1 {
          override def f(x: Int): Long = throw new RuntimeException("boom")
        }

        val ref = Actor.create(logic)
        ref.tell(_.f(5))
        Thread.sleep(1000)
      }
    }

    thrown.getMessage shouldBe "boom"
  }
