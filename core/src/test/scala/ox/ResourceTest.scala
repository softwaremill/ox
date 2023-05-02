package ox

import jdk.incubator.concurrent.ScopedValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import ox.*
import ox.util.Trail

import java.time.Clock
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

class ResourceTest extends AnyFlatSpec with Matchers {
  "useInScope" should "release resources after allocation" in {
    val trail = Trail()

    scoped {
      val r = useInScope { trail.add("allocate"); 1 }(n => trail.add(s"release $n"))
      r shouldBe 1
      trail.trail shouldBe Vector("allocate")
    }
    trail.trail shouldBe Vector("allocate", "release 1")
  }

  it should "release resources in reverse order" in {
    val trail = Trail()

    scoped {
      val r1 = useInScope { trail.add("allocate 1"); 1 }(n => trail.add(s"release $n"))
      val r2 = useInScope { trail.add("allocate 2"); 2 }(n => trail.add(s"release $n"))
      r1 shouldBe 1
      r2 shouldBe 2
      trail.trail shouldBe Vector("allocate 1", "allocate 2")
    }
    trail.trail shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1")
  }

  it should "release resources when there's an exception" in {
    val trail = Trail()

    try
      scoped {
        val r1 = useInScope {
          trail.add("allocate 1"); 1
        }(n => trail.add(s"release $n"))
        val r2 = useInScope {
          trail.add("allocate 2"); 2
        }(n => trail.add(s"release $n"))
        r1 shouldBe 1
        r2 shouldBe 2
        throw new RuntimeException
      }
    catch case _ => trail.add("exception")
    trail.trail shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1", "exception")
  }

  it should "release resources when there's an exception during releasing" in {
    val trail = Trail()

    try
      scoped {
        val r1 = useInScope {
          trail.add("allocate 1");
          1
        } { n =>
          trail.add(s"release $n")
          throw new RuntimeException()
        }
        val r2 = useInScope {
          trail.add("allocate 2");
          2
        } { n =>
          trail.add(s"release $n")
          throw new RuntimeException()
        }
        r1 shouldBe 1
        r2 shouldBe 2
        throw new RuntimeException
      }
    catch case _ => trail.add("exception")
    trail.trail shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1", "exception")
  }

  "useScoped" should "release resources after allocation" in {
    val trail = Trail()
    useScoped {
      trail.add("allocate"); 1
    }(n => trail.add(s"release $n")) { r =>
      r shouldBe 1
      trail.trail shouldBe Vector("allocate")
    }
    trail.trail shouldBe Vector("allocate", "release 1")
  }
}
