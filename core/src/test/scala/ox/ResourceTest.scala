package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

class ResourceTest extends AnyFlatSpec with Matchers {
  "useInScope" should "release resources after allocation" in {
    val trail = Trail()

    unsupervised {
      val r = useInScope { trail.add("allocate"); 1 }(n => trail.add(s"release $n"))
      r shouldBe 1
      trail.get shouldBe Vector("allocate")
    }
    trail.get shouldBe Vector("allocate", "release 1")
  }

  it should "release resources in reverse order" in {
    val trail = Trail()

    unsupervised {
      val r1 = useInScope { trail.add("allocate 1"); 1 }(n => trail.add(s"release $n"))
      val r2 = useInScope { trail.add("allocate 2"); 2 }(n => trail.add(s"release $n"))
      r1 shouldBe 1
      r2 shouldBe 2
      trail.get shouldBe Vector("allocate 1", "allocate 2")
    }
    trail.get shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1")
  }

  it should "release resources when there's an exception" in {
    val trail = Trail()

    try
      unsupervised {
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
    trail.get shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1", "exception")
  }

  it should "release resources when there's an exception during releasing" in {
    val trail = Trail()

    try
      unsupervised {
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
    trail.get shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1", "exception")
  }

  it should "release registered resources" in {
    val trail = Trail()

    unsupervised {
      releaseAfterScope(trail.add("release"))
      trail.add("in scope")
    }
    trail.get shouldBe Vector("in scope", "release")
  }

  it should "use a resource" in {
    val trail = Trail()

    class TestResource {
      trail.add("allocate")
      def release(): Unit = trail.add("release")
    }

    use(new TestResource, _.release()) { r =>
      trail.add("in scope")
    }

    trail.get shouldBe Vector("allocate", "in scope", "release")
  }

  it should "use a closeable resource" in {
    val trail = Trail()

    class TestResource extends AutoCloseable {
      trail.add("allocate")
      def close(): Unit = trail.add("release")
    }

    useCloseable(new TestResource) { r =>
      trail.add("in scope")
    }

    trail.get shouldBe Vector("allocate", "in scope", "release")
  }
}
