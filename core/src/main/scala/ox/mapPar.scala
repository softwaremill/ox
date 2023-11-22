package ox

def mapPar[I, O](parallelism: Int)(iterable: => Iterable[I])(transform: I => O): Iterable[O] = {
  val workers = Math.min(parallelism, iterable.size)
  val elementsInSlide = Math.ceil(iterable.size.toDouble / workers).toInt
  val subCollections = iterable.sliding(elementsInSlide, elementsInSlide)

  supervised {
    val forks = subCollections.toList.map(s => fork {
      s.map(transform)
    })
    forks.flatMap(_.join()).to(Iterable)
  }
}
