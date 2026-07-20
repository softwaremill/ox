package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

class ResourceTest extends AnyFlatSpec with Matchers:
  private val scopeKinds: List[(String, (ResourceScope ?=> Unit) => Unit)] = List(
    "unsupervised" -> (f => unsupervised(f)),
    "resourceScope" -> (f => resourceScope(f))
  )

  for (kind, runScope) <- scopeKinds do
    s"useInScope in $kind" should "release resources after allocation" in {
      val trail = Trail()

      runScope {
        val r = useInScope { trail.add("allocate"); 1 }(n => trail.add(s"release $n"))
        r shouldBe 1
        trail.get shouldBe Vector("allocate")
      }
      trail.get shouldBe Vector("allocate", "release 1")
    }

    it should "release resources in reverse order" in {
      val trail = Trail()

      runScope {
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
        runScope {
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
      end try
      trail.get shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1", "exception")
    }

    it should "release registered resources" in {
      val trail = Trail()

      runScope {
        releaseAfterScope(trail.add("release"))
        trail.add("in scope")
      }
      trail.get shouldBe Vector("in scope", "release")
    }
  end for

  "resourceScope" should "attach resources to the nearest scope when nested in a concurrency scope" in {
    val trail = Trail()
    def inner(): Unit = resourceScope {
      releaseAfterScope(trail.add("inner release"))
      trail.add("inner body")
    }
    supervised {
      releaseAfterScope(trail.add("outer release"))
      inner()
      trail.add("outer body")
    }
    trail.get shouldBe Vector("inner body", "inner release", "outer body", "outer release")
  }

  it should "attach resources to the nearest scope when a concurrency scope is nested inside" in {
    val trail = Trail()
    resourceScope {
      releaseAfterScope(trail.add("outer release"))
      supervised {
        releaseAfterScope(trail.add("inner release"))
        trail.add("inner body")
      }
      trail.add("outer body")
    }
    trail.get shouldBe Vector("inner body", "inner release", "outer body", "outer release")
  }

  it should "throw when registering via a leaked capability after the scope ended" in {
    var leaked: ResourceScope = null
    resourceScope { leaked = summon[ResourceScope] }
    an[IllegalStateException] shouldBe thrownBy(releaseAfterScope(())(using leaked))
  }

  it should "not compile within a visible concurrency scope, but compile via a capability-free method" in {
    "supervised { resourceScope { } }" shouldNot typeCheck
    "def m()(using Ox): Unit = resourceScope { }" shouldNot typeCheck
    "def m(): Unit = resourceScope { }" should compile
    "resourceScope { resourceScope { } }" should compile
  }

  it should "not allow forking without a concurrency scope" in {
    "resourceScope { forkDiscard { } }" shouldNot typeCheck
  }

  it should "release resources when there's an exception during releasing (normal resutl)" in {
    val trail = Trail()

    try
      unsupervised {
        val r1 = useInScope {
          trail.add("allocate 1");
          1
        } { n =>
          trail.add(s"release $n")
          throw new RuntimeException("e1")
        }
        val r2 = useInScope {
          trail.add("allocate 2");
          2
        } { n =>
          trail.add(s"release $n")
          throw new RuntimeException("e2")
        }
        r1 shouldBe 1
        r2 shouldBe 2
        r1 + r2
      }
    catch case e => trail.add(s"exception ${e.getMessage}")
    end try
    trail.get shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1", "exception e2")
  }

  it should "release resources when there's an exception during releasing (exceptional resutl)" in {
    val trail = Trail()

    try
      unsupervised {
        val r1 = useInScope {
          trail.add("allocate 1");
          1
        } { n =>
          trail.add(s"release $n")
          throw new RuntimeException("e1")
        }
        val r2 = useInScope {
          trail.add("allocate 2");
          2
        } { n =>
          trail.add(s"release $n")
          throw new RuntimeException("e2")
        }
        r1 shouldBe 1
        r2 shouldBe 2
        throw new RuntimeException("e3")
      }
    catch case e => trail.add(s"exception ${e.getMessage}")
    end try
    trail.get shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1", "exception e3")
  }

  it should "use a resource" in {
    val trail = Trail()

    class TestResource:
      trail.add("allocate")
      def release(): Unit = trail.add("release")

    use(new TestResource, _.release()) { r =>
      trail.add("in scope")
    }

    trail.get shouldBe Vector("allocate", "in scope", "release")
  }

  it should "use a closeable resource" in {
    val trail = Trail()

    class TestResource extends AutoCloseable:
      trail.add("allocate")
      def close(): Unit = trail.add("release")

    useCloseable(new TestResource) { r =>
      trail.add("in scope")
    }

    trail.get shouldBe Vector("allocate", "in scope", "release")
  }

  it should "add suppressed exception when there's an exception during releasing" in {
    val trail = Trail()

    class TestResource:
      trail.add("allocate")
      def release(): Unit =
        trail.add("release")
        throw new RuntimeException("e1")

    try
      use(new TestResource, _.release()) { r =>
        trail.add("in scope")
        throw new RuntimeException("e2")
      }
    catch case e => trail.add(s"exception ${e.getMessage} (${e.getSuppressed.map(_.getMessage).mkString(", ")})")

    trail.get shouldBe Vector("allocate", "in scope", "release", "exception e2 (e1)")
  }

  "a leaked scope capability" should "throw when registering a resource after the scope ended" in {
    val trail = Trail()
    var leaked: OxUnsupervised = null
    unsupervised {
      leaked = summon[OxUnsupervised]
      trail.add("in scope")
    }
    val e = the[IllegalStateException] thrownBy releaseAfterScope(trail.add("late"))(using leaked)
    e.getMessage should include("has already ended")
    // the release block is run eagerly (so that cleanup is never lost), and the exception is thrown
    trail.get shouldBe Vector("in scope", "late")
  }

  it should "release the acquired resource when registration fails (scope already ended)" in {
    val trail = Trail()
    var leaked: OxUnsupervised = null
    unsupervised { leaked = summon[OxUnsupervised] }
    an[IllegalStateException] shouldBe thrownBy {
      useInScope { trail.add("allocate"); 1 }(n => trail.add(s"release $n"))(using leaked)
    }
    trail.get shouldBe Vector("allocate", "release 1")
  }
end ResourceTest
