package ox.channels

import ox.{discard, Fork, Ox, fork}

/** Fork the given computation, propagating any exceptions to the given sink. The propagated exceptions are not rethrown.
  *
  * Designed to be used in stream operators.
  */
def forkPropagateExceptions[T](propagateExceptionsTo: Sink[_])(f: => Unit)(using Ox): Fork[Unit] =
  fork {
    try f
    catch case t: Throwable => propagateExceptionsTo.errorOrClosed(t).discard
  }
