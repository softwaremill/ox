package warp

import jdk.incubator.concurrent.ScopedValue
import org.slf4j.LoggerFactory

@main def warpTest1(): Unit =
  val log = LoggerFactory.getLogger("warpTest")
  Warp {
    val f1 = Warp.fork {
      Thread.sleep(500)
      log.info("f1 complete")
      5
    }
    val f2 = Warp.fork {
      Thread.sleep(1000)
      log.info("f2 complete")
      6
    }
    log.info("main mid")
    log.info(s"result = ${f1.join() + f2.join()}")
  }

@main def warpTest2(): Unit =
  val log = LoggerFactory.getLogger("warpTest")
  Warp {
    val f1 = Warp.fork {
      val f2 = Warp.fork {
        Thread.sleep(1000)
        log.info("f2 complete")
        6
      }

      Thread.sleep(500)
      log.info("f1 complete")
      5 + f2.join()
    }

    log.info("main mid")
    log.info(s"result = ${f1.join()}")
  }

@main def warpTest3(): Unit =
  val log = LoggerFactory.getLogger("warpTest")
  Warp {
    val f1 = Warp.fork {
      Warp.fork {
        try
          Thread.sleep(1000)
          log.info("f2 complete")
          6
        catch
          case e: InterruptedException =>
            log.info("f2 interrupted")
            throw e
      }

      log.info("f1 complete")
      5
    }

    log.info("main mid")
    log.info(s"result = ${f1.join()}")
  }

@main def warpTest4(): Unit =
  val log = LoggerFactory.getLogger("warpTest")
  val v = Warp.FiberLocal("a")
  Warp {
    val f1 = Warp.fork {
      v.forkWhere("x") {
        Thread.sleep(100L)
        log.info(s"In f1 = ${v.get()}")
      }.join()
      v.get()
    }

    val f3 = Warp.fork {
      v.forkWhere("z") {
        Thread.sleep(100L)
        Warp.fork {
          Thread.sleep(100L)
          log.info(s"In f3 = ${v.get()}")
        }.join()
      }.join()
      v.get()
    }

    log.info("main mid")
    log.info(s"result = ${f1.join()}")
    log.info(s"result = ${f3.join()}")
  }

@main def warpTest5(): Unit =
  val log = LoggerFactory.getLogger("warpTest")
  Warp {
    val f = Warp.fork {
      log.info(s"Fork start")

      Warp.uninterruptible {
        log.info(s"Sleep start")
        Thread.sleep(2000)
        log.info("Sleep done")
      }

      log.info("Fork done")
    }

    Thread.sleep(100)
    log.info("Cancelling ...")
    log.info(s"Cancel result = ${f.cancel()}")
  }
