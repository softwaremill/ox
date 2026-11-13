package ox.flow.csv.codec

/** Provides a default value for fields when a row has fewer columns than expected (ragged rows).
  * Consulted when DisallowRaggedRows is absent on the format (the default for most formats).
  *
  * Instances for Option and T | Null are always in scope (companion object).
  * Primitive defaults (0, "", false, etc.) require `import ox.flow.csv.codec.defaults.missingData.given`. */
@scala.annotation.implicitNotFound(
  """No MissingDataFallback instance for ${T}. Ragged rows require each field type to handle missing columns. Options:
  1. Change field type to Option[${T}] or ${T} | Null (instances provided automatically)
  2. Import primitive defaults: import ox.flow.csv.codec.defaults.missingData.given
  3. Provide your own: given MissingDataFallback[${T}] with { def default = ??? }
  4. Use CsvDecoder[Option[String] => ${T}] to handle both decoding and missing data in one place"""
)
trait MissingDataFallback[+T]:
  def missingFallback: T

object MissingDataFallback:

  given [T]: MissingDataFallback[Option[T]] with
    override def missingFallback: Option[T] = None
  
  given [T]: MissingDataFallback[T | Null] with
    override def missingFallback: T | Null = null
