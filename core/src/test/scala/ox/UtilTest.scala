package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

class UtilTest extends AnyFlatSpec with Matchers {
  "discard" should "do nothing" in {
    val t = Trail()
    def f(): Int =
      t.add("in f")
      42

    f().discard shouldBe ()
    t.get shouldBe Vector("in f")
  }

  "tapException" should "run the callback when an exception is thrown" in {
    val t = Trail()
    def f(): Int = throw new RuntimeException("boom!")

    try f().tapException(e => t.add(s"in callback: ${e.getMessage}"))
    catch case e: RuntimeException => t.add(s"in catch: ${e.getMessage}")

    t.get shouldBe Vector("in callback: boom!", "in catch: boom!")
  }

  it should "not run the callback when no exception is thrown" in {
    val t = Trail()
    def f(): Int = 42

    try
      t.add(f().tapException(e => t.add(s"in callback: ${e.getMessage}")).toString)
      t.add("after")
    catch case e: RuntimeException => t.add(s"in catch: ${e.getMessage}")

    t.get shouldBe Vector("42", "after")
  }

  it should "suppress any additional exceptions" in {
    val t = Trail()

    def f(): Int = throw new RuntimeException("boom!")

    try f().tapException(e => throw new RuntimeException("boom boom!"))
    catch case e: RuntimeException => t.add(s"in catch: ${e.getMessage} ${e.getSuppressed.length}")

    t.get shouldBe Vector("in catch: boom! 1")
  }
}
