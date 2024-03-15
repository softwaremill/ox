package ox

import java.util.concurrent.locks.LockSupport

inline def forever(f: => Unit): Nothing =
  while true do f
  throw new RuntimeException("can't get here")

/** Repeat evaluating `f` while it evaluates to `true`. */
inline def repeatWhile(f: => Boolean): Unit =
  var loop = true
  while loop do loop = f

/** Repeat evaluating `f` until it evaluates to `true`. */
inline def repeatUntil(f: => Boolean): Unit =
  var loop = true
  while loop do loop = !f

inline def uninterruptible[T](f: => T): T =
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
inline def never: Nothing = forever {
  LockSupport.park()
  if Thread.interrupted() then throw new InterruptedException()
}
