package ox.characterization

import ox.sleep

import scala.concurrent.duration.*

import org.slf4j.LoggerFactory

@main def catchInterrupt(): Unit =
  val log = LoggerFactory.getLogger("catchInterrupt")
  val t = new Thread(() =>
    try
      sleep(1.second)
      log.info("T2: Done sleeping (1)")
    catch
      case _: InterruptedException =>
        log.info("T2: Interrupted")

    sleep(1.second)
    log.info("T2: Done sleeping (2)")
  )
  t.start()

  log.info("T1: Started")
  t.interrupt()
  log.info("T1: Interrupted")
  t.join()
  log.info("T1: Done")
end catchInterrupt
