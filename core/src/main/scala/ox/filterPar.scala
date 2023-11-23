package ox

import scala.collection.IterableFactory

/** Runs predicate in parallel on each element of `iterable`. Elements for which predicate returns `true` are returned
 * in the same order as in `iterable`. Elements for which predicate returns `false` are skipped.
 * Using not more than `parallelism` forks concurrently.
 *
 * @tparam I type of elements in `iterable`
 * @tparam C type of `iterable`, must be a subtype of `Iterable`
 * 
 * @param parallelism maximum number of concurrent forks
 * @param iterable collection to filter
 * @param predicate predicate to run on each element of `iterable`
 *                  
 * @return filtered collection
 */
def filterPar[I, C[E] <: Iterable[E]](parallelism: Int)(iterable: => C[I])(predicate: I => Boolean): C[I] =

  def addCalculatedFilter(elem: I): (Boolean, I) =
    (predicate(elem), elem)

  def handleOutputs(outputs: Seq[(Boolean, I)]): C[I] =
    outputs.collect { case (true, elem) => elem }.to(iterable.iterableFactory.asInstanceOf[IterableFactory[C]])

  commonPar(parallelism, iterable, addCalculatedFilter, handleOutputs)

