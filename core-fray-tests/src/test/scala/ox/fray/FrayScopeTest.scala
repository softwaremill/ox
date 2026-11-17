package ox.fray

import org.pastalab.fray.junit.plain.FrayInTestLauncher
import org.scalatest.flatspec.AnyFlatSpec
import ox.*

import java.util.concurrent.atomic.AtomicBoolean

class FrayScopeTest extends AnyFlatSpec:
  // Fray controls platform threads; the factory must be the same instance in all iterations, as it can be set only once
  private val platformThreads = Thread.ofPlatform().factory()

  // https://github.com/softwaremill/ox/issues/552; Fray finds the bug only after https://github.com/cmu-pasta/fray/issues/451 is fixed
  it should "complete a scope only after a fork, which starts other forks, completes" in:
    FrayInTestLauncher.INSTANCE.launchFrayTest: () =>
      setOxThreadFactory(platformThreads)
      val forkCompleted = new AtomicBoolean(false)

      unsupervised:
        forkUnsupervised:
          try for _ <- 1 to 3 do forkUnsupervised(()).discard
          catch case _: IllegalStateException => () // the scope is shutting down
          forkCompleted.set(true)
        .discard

      assert(forkCompleted.get(), "the scope completed, while a fork was still running")
end FrayScopeTest
