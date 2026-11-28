package ox.channels

// On Scala Native, channels are implemented by the Scala port of jox in `ox.channels.jox`.

private[ox] type JChannel[T] = jox.Channel[T]
private[ox] type JSource[T] = jox.Source[T]
private[ox] type JSink[T] = jox.Sink[T]
private[ox] type JSelectClause[T] = jox.SelectClause[T]

private[ox] object JoxPlatform:
  def newChannel(capacity: Int): JChannel[Any] = capacity match
    case 0  => jox.Channel.newRendezvousChannel()
    case -1 => jox.Channel.newUnlimitedChannel()
    case _  => jox.Channel.newBufferedChannel(capacity)

  def defaultClause[T](callback: () => T): JSelectClause[T] = jox.Select.defaultClause(callback)

  def selectOrClosed(clauses: Seq[JSelectClause[Any]]): AnyRef = jox.Select.selectOrClosed(clauses*)

  /** Converts jox's closed markers to [[ChannelClosed]], passing other values through. */
  def fromJox(joxResult: AnyRef): AnyRef | ChannelClosed = joxResult match
    case _: jox.ChannelDone  => ChannelClosed.Done
    case e: jox.ChannelError => ChannelClosed.Error(e.cause)
    case _                   => joxResult
end JoxPlatform
