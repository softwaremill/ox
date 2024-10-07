package ox.characterization

import ox.sleep

import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import java.util.concurrent.StructuredTaskScope

@main def nestedScopes(): Unit =
  val log = LoggerFactory.getLogger("nestedScopes")
  val scope1 = new StructuredTaskScope.ShutdownOnFailure()
  log.info("Started scope1")
  try
    scope1.fork { () =>
      sleep(1.second)
      log.info("Child 1 done")
    }
    Thread.startVirtualThread { () =>
      sleep(3.seconds)
      log.info("Child 2 done")
    }
    val scope2 = new StructuredTaskScope.ShutdownOnFailure()
    log.info("Started scope2")
    try
      scope2.fork { () =>
        sleep(2.seconds)
        log.info("Child 3 done")
      }
      scope1.join()
      log.info("Scope 1 joined")
      scope2.join()
      log.info("Scope 2 joined")
    finally
      scope2.close()
      log.info("Scope 2 closed")
    end try
  finally
    scope1.close()
    log.info("Scope 1 closed")
  end try

  sleep(5.seconds)
end nestedScopes
