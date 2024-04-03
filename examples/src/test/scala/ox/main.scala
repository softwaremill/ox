package ox

import org.slf4j.LoggerFactory

import scala.concurrent.duration.*

@main def test1 =
  val log = LoggerFactory.getLogger("test1")
  val r = supervised {
    val f1 = fork {
      sleep(1.second)
      log.info("f1 done")
      5
    }
    val f2 = fork {
      sleep(2.seconds)
      log.info("f2 done")
      6
    }
    f1.join() + f2.join()
  }
  log.info("result: " + r)
