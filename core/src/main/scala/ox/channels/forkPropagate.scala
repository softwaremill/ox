package ox.channels

import ox.*

/** Fork the given computation, propagating any exceptions to the given sink. The propagated exceptions are not rethrown.
  *
  * Designed to be used in stream operators.
  *
  * @see
  *   ADR#1, ADR#3, implementation note in [[SourceOps]].
  */
def forkPropagate[T](propagateExceptionsTo: Sink[?])(f: => Unit)(using OxUnsupervised): Fork[Unit] =
  forkUnsupervised:
    try f
    catch case t: Throwable => propagateExceptionsTo.errorOrClosed(t).discard

/** A variant of [[forkPropagate]] which uses [[forkUser]]. */
def forkUserPropagate[T](propagateExceptionsTo: Sink[?])(f: => Unit)(using Ox): Fork[Unit] =
  forkUser:
    try f
    catch case t: Throwable => propagateExceptionsTo.errorOrClosed(t).discard
