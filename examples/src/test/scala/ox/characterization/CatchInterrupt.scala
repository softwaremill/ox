package ox.characterization

import org.slf4j.LoggerFactory

@main def catchInterrupt(): Unit =
  val log = LoggerFactory.getLogger("catchInterrupt")
  val t = new Thread(() =>
    try
      Thread.sleep(1000L)
      log.info("T2: Done sleeping (1)")
    catch
      case _: InterruptedException =>
        log.info("T2: Interrupted")

    Thread.sleep(1000L)
    log.info("T2: Done sleeping (2)")
  )
  t.start()

  log.info("T1: Started")
  t.interrupt()
  log.info("T1: Interrupted")
  t.join()
  log.info("T1: Done")
