package ox.flow.csv.codec

/** Provides a default value for fields when a row has fewer columns than expected (ragged rows). Consulted when DisallowRaggedRows is
  * absent on the format (the default for most formats).
  *
  * An instance for Option is always in scope (companion object). Primitive defaults (0, "", false, etc.) require
  * `import ox.flow.csv.codec.defaults.missingData.given`.
  */
@scala.annotation.implicitNotFound(
  """No MissingDataFallback instance for ${T}. Ragged rows require each field type to handle missing columns. Options:
  1. Change field type to Option[${T}] (instance provided automatically)
  2. Import primitive defaults: import ox.flow.csv.codec.defaults.missingData.given
  3. Provide your own: given MissingDataFallback[${T}]"""
)
trait MissingDataFallback[+T]:
  def missingFallback: T
end MissingDataFallback

object MissingDataFallback:

 
  given [T]: MissingDataFallback[Option[T]] with
    override def missingFallback: Option[T] = None
