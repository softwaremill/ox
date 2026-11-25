package ox.fray

import org.pastalab.fray.junit.plain.FrayInTestLauncher
import org.scalatest.flatspec.AnyFlatSpec

import java.util.concurrent.atomic.AtomicInteger

/** Verifies that Fray is set up correctly: it should find a simple race condition. */
class FrayExampleTest extends AnyFlatSpec:
  it should "find a lost update, when two threads increment a counter non-atomically" in:
    val e = intercept[Throwable]:
      FrayInTestLauncher.INSTANCE.launchFrayTest: () =>
        val counter = new AtomicInteger(0)
        val threads = (1 to 2).map(_ => Thread.ofPlatform().start(() => counter.set(counter.get() + 1)))
        threads.foreach(_.join())
        assert(counter.get() == 2)

    assert(e.getMessage.contains("1 did not equal 2"))
end FrayExampleTest
