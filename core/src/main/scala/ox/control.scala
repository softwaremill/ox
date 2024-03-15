package ox

import java.util.concurrent.locks.LockSupport

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

/** Blocks the current thread indefinitely, until it is interrupted. */
def never: Nothing = forever {
  LockSupport.park()
  if Thread.interrupted() then throw new InterruptedException()
}
