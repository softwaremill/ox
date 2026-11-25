package ox.fray

import java.util.concurrent.ThreadFactory

/** Fray only controls platform threads, so Ox must use them. As the Ox thread factory can be set only once per JVM, all Fray tests must use
  * this instance.
  */
object FrayThreads:
  val factory: ThreadFactory = Thread.ofPlatform().factory()
