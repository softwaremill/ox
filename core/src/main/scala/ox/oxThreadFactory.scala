package ox

import java.util.concurrent.ThreadFactory

private var customThreadFactory: ThreadFactory = _

/** @see oxThreadFactory */
def setOxThreadFactory(tf: ThreadFactory): Unit =
  customThreadFactory = tf
  if oxThreadFactory != customThreadFactory then
    throw new RuntimeException("The thread factory was already used before setting a custom one!")

/** The thread factory that is used to create threads in Ox scopes ([[supervised]], [[unsupervised]] etc.). Should be set once at the start
  * of the application, before any scopes or forks are created, using [[setOxThreadFactory]].
  */
lazy val oxThreadFactory: ThreadFactory =
  val custom = customThreadFactory
  if custom == null then Thread.ofVirtual().factory() else custom
