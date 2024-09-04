package ox.channels

import ox.*

/** Fork the given computation, propagating any exceptions to the given sink. The propagated exceptions are not rethrown.
  *
  * Designed to be used in stream operators.
  *
  * @see
  *   ADR#1, ADR#3, implementation note in [[SourceOps]].
  */
def forkPropagate[T](propagateExceptionsTo: Sink[_])(f: => Unit)(using Ox): Fork[Unit] =
  fork {
    try f
    catch case t: Throwable => propagateExceptionsTo.errorOrClosed(t).discard
  }
