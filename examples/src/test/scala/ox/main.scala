package ox

import org.slf4j.LoggerFactory

@main def test1 =
  val log = LoggerFactory.getLogger("test1")
  val r = scoped {
    val f1 = forkHold {
      Thread.sleep(1000L)
      log.info("f1 done")
      5
    }
    val f2 = forkHold {
      Thread.sleep(2000L)
      log.info("f2 done")
      6
    }
    f1.join() + f2.join()
  }
  log.info("result: " + r)
