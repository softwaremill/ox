package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsHeadTest extends AnyFlatSpec with Matchers {
  behavior of "Source.head"

  it should "throw NoSuchElementException for the empty source" in supervised {
    the[NoSuchElementException] thrownBy {
      Source.empty[Int].head()
    } should have message "cannot obtain head from an empty source"
  }

  it should "re-throw exception that was thrown during element retrieval" in supervised {
    the[RuntimeException] thrownBy {
      Source
        .failed(new RuntimeException("source is broken"))
        .head()
    } should have message "source is broken"
  }

  it should "throw NoSuchElementException for source failed without exception" in supervised {
    the[NoSuchElementException] thrownBy {
      Source.failedWithoutReason[Int]().head()
    } should have message "getting head failed"
  }

  it should "return first value from non empty source" in supervised {
    Source.fromValues(1, 2).head() shouldBe 1
  }

  it should "be not idempotent operation" in supervised {
    val s = Source.fromValues(1, 2)
    s.head() shouldBe 1
    s.head() shouldBe 2
  }
}
