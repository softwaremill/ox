package ox.flow.csv.codec

/** Provides a value for fields when the format's null string is matched (e.g. \N in PostgreSQL).
  *
  * Instances for Option and T | Null are always in scope (companion object).
  * Primitive defaults (0, "", false, etc.) require `import ox.flow.csv.codec.defaults.nullData.given`. */
@scala.annotation.implicitNotFound(
  """No NullData instance for ${T}. When a format defines a null string, each field type must handle null values. Options:
  1. Change field type to Option[${T}] or ${T} | Null (instances provided automatically)
  2. Import primitive defaults: import ox.flow.csv.codec.defaults.nullData.given
  3. Provide your own: given NullData[${T}] with { def value = ??? }
  4. Use CsvDecoder[Option[String] => ${T}] to handle both decoding and null data in one place"""
)
trait NullFallback[+T]:
  def nullFallback: T

object NullFallback:

  given [T]: NullFallback[Option[T]] with
    override def nullFallback: Option[T] = None

  given [T]: NullFallback[T | Null] with
    override def nullFallback: T | Null = null
