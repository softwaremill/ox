package ox

import scala.collection.IterableFactory

/** Runs parallel transformations on `iterable`. Using not more than `parallelism` forks concurrently.
 *
 * @tparam I type of elements in `iterable`
 * @tparam O type of elements in result
 * @tparam C type of `iterable`, must be a subtype of `Iterable`
 *
 * @param parallelism maximum number of concurrent forks
 * @param iterable collection to transform
 * @param transform transformation to apply to each element of `iterable`
 *                  
 * @return transformed collection of the same type as input one
 */
def mapPar[I, O, C[E] <: Iterable[E]](parallelism: Int)(iterable: => C[I])(transform: I => O): C[O] =

  def handleOutputs(outputs: Seq[O]): C[O] =
    outputs.to(iterable.iterableFactory.asInstanceOf[IterableFactory[C]])

  commonPar(parallelism, iterable, transform, handleOutputs)

