package ox

import scala.annotation.unused

/**
 * Parallelize a foreach operation. Runs the operation on each element of the iterable in parallel.
 * Using not more than `parallelism` forks concurrently.
 *
 * @tparam I the type of the elements in the iterable
 * @tparam C the type of the iterable, must be a subtype of Iterable[I]
 *
 * @param parallelism the number of threads to use
 * @param iterable the collection to iterate over
 * @param operation the operation to perform on each element
 */
def foreachPar[I, C <: Iterable[I]](parallelism: Int)(iterable: => C)(operation: I => Any): Unit =
  def handleOutputs(@unused outputs: Seq[_]): Unit = ()

  commonPar(parallelism, iterable, operation, handleOutputs)

