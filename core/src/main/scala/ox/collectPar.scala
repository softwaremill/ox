package ox

import scala.collection.IterableFactory

/** Runs partial function in parallel on each element of `iterable` for which the partial function is defined.
 * If function is not defined for an element such element is skipped.
 * Using not more than `parallelism` forks concurrently.
 *
 * @tparam I type of elements of `iterable`
 * @tparam O type of elements of result
 * @tparam C type of `iterable`, must be a subtype of `Iterable`
 *
 * @param parallelism maximum number of concurrent forks
 * @param iterable collection to transform
 * @param pf partial function to apply to those elements of `iterable` for which it is defined
 *
 * @return collection of results of applying `pf` to elements of `iterable` for which it is defined. The returned
 *         collection is of the same type as `iterable`
 */
def collectPar[I, O, C[E] <: Iterable[E]](parallelism: Int)(iterable: => C[I])(pf: PartialFunction[I, O]): C[O] =

  def nonPartialOperation(elem: I): Option[O] =
    if pf.isDefinedAt(elem) then
      Some(pf(elem))
    else
      None

  def handleOutputs(outputs: Seq[Option[O]]): C[O] =
    outputs.collect { case Some(output) => output }.to(iterable.iterableFactory.asInstanceOf[IterableFactory[C]])

  commonPar(parallelism, iterable, nonPartialOperation, handleOutputs)

