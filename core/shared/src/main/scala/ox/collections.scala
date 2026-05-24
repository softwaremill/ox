package ox

import scala.annotation.unused
import scala.collection.IterableFactory

import java.util.concurrent.Semaphore

private[ox] def commonPar[I, O, C[E] <: Iterable[E], FO](
    parallelism: Int,
    iterable: => C[I],
    transform: I => O,
    handleOutputs: Seq[O] => FO
): FO =
  val s = Semaphore(parallelism)

  supervised {
    val forks = iterable.map { elem =>
      s.acquire()
      fork {
        val o = transform(elem)
        s.release()
        o
      }
    }
    val outputs = forks.toSeq.map(f => f.join())
    handleOutputs(outputs)
  }
end commonPar

extension [I, C[E] <: Iterable[E]](iterable: C[I])
  /** Runs partial function in parallel on each element of `iterable` for which the partial function is defined. If function is not defined
    * for an element such element is skipped. Using not more than `parallelism` forks concurrently.
    *
    * @tparam O
    *   type of elements of result
    *
    * @param parallelism
    *   maximum number of concurrent forks
    * @param pf
    *   partial function to apply to those elements of `iterable` for which it is defined
    *
    * @return
    *   collection of results of applying `pf` to elements of `iterable` for which it is defined. The returned collection is of the same
    *   type as `iterable`
    */
  def collectPar[O](parallelism: Int)(pf: PartialFunction[I, O]): C[O] =

    def nonPartialOperation(elem: I): Option[O] =
      if pf.isDefinedAt(elem) then Some(pf(elem))
      else None

    def handleOutputs(outputs: Seq[Option[O]]): C[O] =
      outputs.collect { case Some(output) => output }.to(iterable.iterableFactory.asInstanceOf[IterableFactory[C]])

    commonPar(parallelism, iterable, nonPartialOperation, handleOutputs)
  end collectPar

  /** Runs predicate in parallel on each element of `iterable`. Elements for which predicate returns `true` are returned in the same order
    * as in `iterable`. Elements for which predicate returns `false` are skipped. Using not more than `parallelism` forks concurrently.
    *
    * @param parallelism
    *   maximum number of concurrent forks
    * @param predicate
    *   predicate to run on each element of `iterable`
    *
    * @return
    *   filtered collection
    */
  def filterPar(parallelism: Int)(predicate: I => Boolean): C[I] =

    def addCalculatedFilter(elem: I): (Boolean, I) =
      (predicate(elem), elem)

    def handleOutputs(outputs: Seq[(Boolean, I)]): C[I] =
      outputs.collect { case (true, elem) => elem }.to(iterable.iterableFactory.asInstanceOf[IterableFactory[C]])

    commonPar(parallelism, iterable, addCalculatedFilter, handleOutputs)
  end filterPar

  /** Parallelize a foreach operation. Runs the operation on each element of the iterable in parallel. Using not more than `parallelism`
    * forks concurrently.
    *
    * @param parallelism
    *   the number of threads to use
    * @param operation
    *   the operation to perform on each element
    */
  def foreachPar(parallelism: Int)(operation: I => Any): Unit =
    def handleOutputs(@unused outputs: Seq[?]): Unit = ()

    commonPar(parallelism, iterable, operation, handleOutputs)

  /** Runs parallel transformations on `iterable`. Using not more than `parallelism` forks concurrently.
    *
    * @tparam O
    *   type of elements in result
    *
    * @param parallelism
    *   maximum number of concurrent forks
    * @param transform
    *   transformation to apply to each element of `iterable`
    *
    * @return
    *   transformed collection of the same type as input one
    */
  def mapPar[O](parallelism: Int)(transform: I => O): C[O] =

    def handleOutputs(outputs: Seq[O]): C[O] =
      outputs.to(iterable.iterableFactory.asInstanceOf[IterableFactory[C]])

    commonPar(parallelism, iterable, transform, handleOutputs)
end extension
