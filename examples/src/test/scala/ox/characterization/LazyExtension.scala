package ox.characterization

import ox.discard

@main def lazyExtension(): Unit =
  extension [T](t: => T)
    def test: T =
      println("In extension")
      t

  {
    println("In block")
    1
  }.test.discard
