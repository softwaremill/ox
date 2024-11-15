package ox.channels

import ox.*

/** Fork the given computation, propagating any exceptions to the given sink. The propagated exceptions are not rethrown. The fork is run
  * only for its side effects, and the result is discarded (can't be joined, same as [[forkDiscard]]).
  *
  * Designed to be used in stream operators.
  *
  * @see
  *   ADR#1, ADR#3, implementation note in [[SourceOps]].
  */
def forkPropagate[T](propagateExceptionsTo: Sink[?])(f: => Unit)(using OxUnsupervised): Unit =
  forkUnsupervised:
    try f
    catch case t: Throwable => propagateExceptionsTo.errorOrClosed(t).discard
  .discard
