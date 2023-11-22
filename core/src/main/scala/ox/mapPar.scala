package ox

import scala.collection.IterableFactory

def mapPar[I, O, C[E] <: Iterable[E]](parallelism: Int)(iterable: => C[I])(transform: I => O): C[O] =
  val workers = Math.min(parallelism, iterable.size)
  val elementsInSlide = Math.ceil(iterable.size.toDouble / workers).toInt
  val subCollections = iterable.sliding(elementsInSlide, elementsInSlide)

  supervised {
    val forks = subCollections.toList.map(s => fork(s.map(transform)))
    forks.flatMap(_.join()).to(iterable.iterableFactory.asInstanceOf[IterableFactory[C]])
  }
