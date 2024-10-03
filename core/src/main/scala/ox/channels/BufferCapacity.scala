package ox.channels

/** Used to determine the capacity of buffers, when new channels are created by channel or flow-transforming operations, such as
  * [[Source.map]], [[Flow.async]], [[Flow.runToChannel]]. If not in scope, the default of 16 is used.
  */
opaque type BufferCapacity = Int

object BufferCapacity:
  def apply(c: Int): BufferCapacity = c
  def newChannel[T](using BufferCapacity): Channel[T] = Channel.withCapacity[T](summon[BufferCapacity])
  given default: BufferCapacity = 16
