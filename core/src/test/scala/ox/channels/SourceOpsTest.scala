package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import java.util.concurrent.atomic.AtomicInteger

class SourceOpsTest extends AnyFlatSpec with Matchers with Eventually:

  it should "pipe one source to another" in {
    supervised {
      val c1 = Source.fromValues(1, 2, 3)
      val c2 = Channel.rendezvous[Int]

      fork {
        c1.pipeTo(c2, propagateDone = false)
        c2.done()
      }

      c2.toList shouldBe List(1, 2, 3)
    }
  }

  it should "pipe one source to another (with done propagation)" in {
    supervised {
      val c1 = Source.fromValues(1, 2, 3)
      val c2 = Channel.rendezvous[Int]

      fork {
        c1.pipeTo(c2, propagateDone = true)
      }

      c2.toList shouldBe List(1, 2, 3)
    }
  }

  it should "tap over a source" in {
    supervised {
      val sum = new AtomicInteger()
      Source.fromValues(1, 2, 3).tap(v => sum.addAndGet(v).discard).toList shouldBe List(1, 2, 3)
      sum.get() shouldBe 6
    }
  }
end SourceOpsTest
