package ox

import scala.concurrent.duration.FiniteDuration

def forever(f: => Unit): Nothing =
  while true do f
  throw new RuntimeException("can't get here")

/** Repeat evaluating `f` while it evaluates to `true`. */
def repeatWhile(f: => Boolean): Unit =
  var loop = true
  while loop do loop = f

/** Repeat evaluating `f` until it evaluates to `true`. */
def repeatUntil(f: => Boolean): Unit =
  var loop = true
  while loop do loop = !f

// TODO: retry schedules
def retry[T](times: Int, sleep: FiniteDuration)(f: => T): T =
  try f
  catch
    case e: Throwable =>
      if times > 0
      then
        Thread.sleep(sleep.toMillis)
        retry(times - 1, sleep)(f)
      else throw e

def retryEither[E, T](times: Int, sleep: FiniteDuration)(f: => Either[E, T]): Either[E, T] =
  f match
    case r: Right[E, T] => r
    case Left(_) if times > 0 =>
      Thread.sleep(sleep.toMillis)
      retry(times - 1, sleep)(f)
    case l: Left[E, T] => l

def uninterruptible[T](f: => T): T =
  scoped {
    val t = fork(f)

    def joinDespiteInterrupted: T =
      try t.join()
      catch
        case e: InterruptedException =>
          joinDespiteInterrupted
          throw e

    joinDespiteInterrupted
  }
