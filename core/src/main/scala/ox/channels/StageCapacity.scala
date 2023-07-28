package ox.channels

opaque type StageCapacity = Int

object StageCapacity:
  def apply(c: Int) = c
  def newChannel[T](using StageCapacity): Channel[T] = Channel[T](summon[StageCapacity])
  given default: StageCapacity = 0
