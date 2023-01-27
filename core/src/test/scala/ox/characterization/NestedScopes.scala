package ox

import jdk.incubator.concurrent.StructuredTaskScope
import org.slf4j.LoggerFactory

@main def nestedScopes(): Unit =
  val log = LoggerFactory.getLogger("nestedScopes")
  val scope1 = new StructuredTaskScope.ShutdownOnFailure()
  log.info("Started scope1")
  try
    scope1.fork { () =>
      Thread.sleep(1000L)
      log.info("Child 1 done")
    }
    Thread.startVirtualThread { () =>
      Thread.sleep(3000L)
      log.info("Child 2 done")
    }
    val scope2 = new StructuredTaskScope.ShutdownOnFailure()
    log.info("Started scope2")
    try
      scope2.fork { () =>
        Thread.sleep(2000L)
        log.info("Child 3 done")
      }
      scope1.join()
      log.info("Scope 1 joined")
      scope2.join()
      log.info("Scope 2 joined")
    finally
      scope2.close()
      log.info("Scope 2 closed")
  finally
    scope1.close()
    log.info("Scope 1 closed")

  Thread.sleep(5000L)
