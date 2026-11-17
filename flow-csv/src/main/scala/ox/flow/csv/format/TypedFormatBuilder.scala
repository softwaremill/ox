package ox.flow.csv.format

import ox.flow.csv.compat.ApacheCsvFormatCompat
import ox.flow.csv.format.internal.macros
import ox.flow.csv.format.{Format, HeaderMode, Settings}

import scala.annotation.publicInBinary
import scala.deriving.Mirror.ProductOf

trait HasBuilder[S]:
  def builder: TypedFormatBuilder[S]

trait TypedFormatBuilder[S]:

  /** The dialect accumulated so far, as plain data. A DSL step copies this and widens `S`; the Apache
    * format is materialised once, by [[build]], since constructing it is what validates the settings. */
  @publicInBinary
  private [csv] def settings: Settings

  /** Builds the format for `T` from the settings `S` carries.
    *
    * `S` decides both the stream shape and which typeclasses each field must provide:
    *   - [[NullString]] present: cells can arrive as `null`, so a [[ox.flow.csv.codec.NullFallback]] is required
    *   - [[DisallowRaggedRows]] absent: trailing cells can be missing, so a
    *     [[ox.flow.csv.codec.MissingDataFallback]] is required
    *   - [[HeaderModeSetting]] present: the stream may carry a [[ox.flow.csv.CsvRecord.HeaderRecord]]
    *
    * `transparent` so the caller gets the precise format type back. The reduction needs `T` to be
    * concrete, which it is at a call site; the predefined formats instead call the shape-specific
    * builders in [[Format]] directly, since they know their shape statically.
    */
  transparent inline def build[T](using mir: ProductOf[T]): Any = //builds PrinterFormat or ParserFormat or Both with HasBuilder
    // building the Apache format is what validates the settings
    val apacheFormat = ApacheCsvFormatCompat.toApache(settings)
    inline if macros.isSubtypeOf[S, HeaderModeSetting[HeaderMode.Force]] then
      Format.forcedHeader[T, S](apacheFormat, this)
    else inline if macros.isSubtypeOf[S, HeaderModeSetting[?]] then
      Format.optionalHeader[T, S](apacheFormat, this)
    else Format.noHeader[T, S](apacheFormat, this)

object TypedFormatBuilder:

  private final class Instance[S](@publicInBinary val settings: Settings) extends TypedFormatBuilder[S]

  @publicInBinary
  private [csv] def unsafeFromSettings[S](settings: Settings) :TypedFormatBuilder[S] = new Instance(settings)
