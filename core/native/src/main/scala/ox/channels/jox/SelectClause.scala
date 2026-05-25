package ox.channels.jox

/** A clause to use as part of `Select.select`. */
abstract class SelectClause[T]:
  private[jox] def getChannel: Channel[?] | Null = null

  /** Returns a StoredSelectClause, ChannelClosed, or the selected value (not null). */
  private[jox] def register(select: SelectInstance): AnyRef

  /** Transforms the raw value using the transformation function provided when creating the clause. */
  private[jox] def transformedRawValue(rawValue: AnyRef): T
end SelectClause

private[jox] abstract class DefaultClause[T] extends SelectClause[T]:
  override private[jox] def register(select: SelectInstance): AnyRef = this

private[jox] final class DefaultClauseValue[T](value: T) extends DefaultClause[T]:
  override private[jox] def transformedRawValue(rawValue: AnyRef): T = value

private[jox] final class DefaultClauseCallback[T](callback: () => T) extends DefaultClause[T]:
  override private[jox] def transformedRawValue(rawValue: AnyRef): T = callback()
