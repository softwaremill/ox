package ox.channels

/** Used to determine the capacity of internal processing stages, when new channels are created by channel-transforming operations, such as
  * [[Source.map]]. If not in scope, the default of 16 is used.
  */
opaque type StageCapacity = Int

object StageCapacity:
  def apply(c: Int): StageCapacity = c
  def newChannel[T](using StageCapacity): Channel[T] = Channel[T](summon[StageCapacity])
  given default: StageCapacity = 16
