package ox.flow.csv.format.typed

import ox.flow.csv.format.compat.ApacheCsvFormatCompat
import ox.flow.csv.format.{HeaderMode, ParserFormat, PrinterFormat, Settings}

import scala.annotation.publicInBinary
import scala.deriving.Mirror.ProductOf

trait HasBuilder[S]:
  def builder: TypedFormatBuilder[S]

trait TypedFormatBuilder[S]:

  /** The dialect accumulated so far, as plain data. A DSL step copies this and widens `S`; the Apache
    * format is materialised once, by [[build]], since constructing it is what validates the settings. */
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
    * builders in [[FlowFormatImpl]] directly, since they know their shape statically.
    */
  transparent inline def build[T](using mir: ProductOf[T]): Any =
    // building the Apache format is what validates the settings
    val apacheFormat = ApacheCsvFormatCompat.toApache(settings)
    inline if SettingsQuery.hasSetting[S, HeaderModeSetting[HeaderMode.Force]] then
      FlowFormatImpl.forcedHeader[T, S](apacheFormat, this)
    else inline if SettingsQuery.hasSetting[S, HeaderModeSetting[?]] then
      FlowFormatImpl.optionalHeader[T, S](apacheFormat, this)
    else FlowFormatImpl.noHeader[T, S](apacheFormat, this)

object TypedFormatBuilder:

  /** The builder a format hands back: the settings it was built from, at the settings it was built at.
    *
    * The class is not `private[csv]`: a predefined format's `inline` body expands at the call site, so both
    * the selection and the constructor have to resolve from outside the package — hence `@publicInBinary`.
    */
  final class Instance[S] @publicInBinary private[csv] (val settings: Settings) extends TypedFormatBuilder[S]
