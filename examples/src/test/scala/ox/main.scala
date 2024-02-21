package ox

import org.slf4j.LoggerFactory

@main def test1 =
  val log = LoggerFactory.getLogger("test1")
  val r = supervised {
    val f1 = fork {
      Thread.sleep(1000L)
      log.info("f1 done")
      5
    }
    val f2 = fork {
      Thread.sleep(2000L)
      log.info("f2 done")
      6
    }
    f1.join() + f2.join()
  }
  log.info("result: " + r)
