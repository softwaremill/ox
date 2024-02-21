package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsFactoryMethodsTest extends AnyFlatSpec with Matchers {

  behavior of "Source factory methods"

  it should "create a source from a fork" in {
    supervised {
      val f = fork(1)
      val c = Source.fromFork(f)
      c.toList shouldBe List(1)
    }
  }

  it should "create an iterating source" in {
    supervised {
      val c = Source.iterate(1)(_ + 1)
      c.take(3).toList shouldBe List(1, 2, 3)
    }
  }

  it should "unfold a function" in {
    supervised {
      val c = Source.unfold(0)(i => if i < 3 then Some((i, i + 1)) else None)
      c.toList shouldBe List(0, 1, 2)
    }
  }

  it should "produce a range" in {
    supervised {
      Source.range(1, 5, 1).toList shouldBe List(1, 2, 3, 4, 5)
      Source.range(1, 5, 2).toList shouldBe List(1, 3, 5)
      Source.range(1, 11, 3).toList shouldBe List(1, 4, 7, 10)
    }
  }
}
