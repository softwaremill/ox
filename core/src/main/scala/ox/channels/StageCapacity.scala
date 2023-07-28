package ox.channels

opaque type StageCapacity = Int

object StageCapacity:
  def apply(c: Int) = c
  extension (c: StageCapacity) def toInt: Int = c
  given default: StageCapacity = 0
