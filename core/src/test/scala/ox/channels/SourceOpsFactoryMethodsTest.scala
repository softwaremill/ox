package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsFactoryMethodsTest extends AnyFlatSpec with Matchers:

  behavior of "Source factory methods"

  it should "create a source from a fork" in {
    supervised {
      val f = fork(1)
      val c = Source.fromFork(f)
      c.toList shouldBe List(1)
    }
  }
end SourceOpsFactoryMethodsTest
