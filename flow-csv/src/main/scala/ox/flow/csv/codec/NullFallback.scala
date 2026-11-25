package ox.flow.csv.codec

/** Provides a value for fields when the format's null string is matched.
  *
  * An instance for Option is always in scope (companion object). Primitive defaults (0, "", false, etc.) require
  * `import ox.flow.csv.codec.defaults.nullData.given`.
  * @see
  *   [[MissingDataFallback]]
  * @see
  *   [[CsvDecoder]]
  */
@scala.annotation.implicitNotFound(
  """No NullData instance for ${T}. When a format defines a null string, each field type must handle null values. Options:
  1. Change field type to Option[${T}] (instance provided automatically)
  2. Import primitive defaults: import ox.flow.csv.codec.defaults.nullData.given
  3. Provide your own: given NullData[${T}]"""
)
trait NullFallback[+T]:
  def nullFallback: T
end NullFallback

object NullFallback:

  given [T]: NullFallback[Option[T]] with
    override def nullFallback: Option[T] = None
