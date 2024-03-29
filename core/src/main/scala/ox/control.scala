package ox

import java.util.concurrent.locks.LockSupport

inline def forever(inline f: Unit): Nothing =
  while true do f
  throw new RuntimeException("can't get here")

/** Repeat evaluating `f` while it evaluates to `true`. */
inline def repeatWhile(inline f: Boolean): Unit =
  var loop = true
  while loop do loop = f

/** Repeat evaluating `f` until it evaluates to `true`. */
inline def repeatUntil(inline f: Boolean): Unit =
  var loop = true
  while loop do loop = !f

/** Blocks the current thread indefinitely, until it is interrupted. */
inline def never: Nothing = forever {
  LockSupport.park()
  if Thread.interrupted() then throw new InterruptedException()
}
