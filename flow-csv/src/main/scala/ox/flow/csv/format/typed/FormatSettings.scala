package ox.flow.csv.format.typed

import ox.flow.csv.format.HeaderMode

/** When present, truly empty lines (no characters before line separator) are silently dropped.
  * Quoted empty strings (`""`) are preserved.
  *
  * '''Edge case''': for single-column String types, an unquoted empty value is indistinguishable from an empty line
  * and will be dropped — data loss. Use a format without this setting (e.g. RFC4180) if single-column empty strings matter. */
trait IgnoreEmptyLines
trait NullString[S <: String]
trait HeaderModeSetting[H <: HeaderMode]
trait CommentMarker[C <: Char]
/** When present, rows with fewer columns than the target type fail at runtime. When absent, missing trailing fields are handled via [[ox.flow.csv.codec.MissingDataFallback]]. */
trait DisallowRaggedRows
