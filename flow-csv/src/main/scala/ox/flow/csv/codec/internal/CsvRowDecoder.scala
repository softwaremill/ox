package ox.flow.csv.codec.internal


import ox.flow.csv.codec.CsvException.{FieldDecodeException, FormatViolationException}
import ox.flow.csv.codec.{CsvDecoder, MissingDataFallback, NullFallback}

import scala.annotation.publicInBinary
import scala.compiletime.*
import scala.deriving.Mirror.ProductOf
import scala.util.control.NonFatal

/** Decodes one CSV row into `T`.
  *
  * A row is `IArray[String | Null]`: Apache yields `null` for cells matching the format's null string, and a row shorter than the field
  * count means the trailing fields are missing. Both are resolved per field: missing -> [[MissingDataFallback]], null -> [[NullFallback]],
  * otherwise [[CsvDecoder]].
  */
private[csv] trait CsvRowDecoder[+T]:
  /** How many cells a full row has: the field count of `T`. */
  private[csv] def size: Int

  /** Decodes one row.
    *
    * @throws ox.flow.csv.codec.FieldDecodeException
    *   if a [[CsvDecoder]] rejects a cell. The decoder's own exception is the cause: it reports what was wrong with the value, while this
    *   adds the column it came from.
    * @throws ox.flow.csv.codec.FormatViolationException
    *   if the row does not fit the format: a column is missing where ragged rows are disallowed, a cell is `null` where the format defines
    *   no null string, or the row has more cells than `T` has fields.
    */
  def decodeRow(row: IArray[String | Null]): T

@publicInBinary
private[csv] object CsvRowDecoder:
  /** Per-field decoders, already specialised to what the format can produce. */
  private[csv] final class Impl[T](
      decoders: IArray[CsvDecoder[Any]],
      missing: IArray[MissingDataFallback[Any] | Null],
      nulls: IArray[NullFallback[Any] | Null],
      mir: ProductOf[T]
  ) extends CsvRowDecoder[T]:
    private[csv] val size = decoders.length

    def decodeRow(row: IArray[String | Null]): T =
      // a local mutable buffer, handed straight to `fromProduct` and never seen again, so it stays an Array
      val result = new Array[Any](size)
      var i = 0
      while i < size do
        result(i) =
          if i >= row.length then
            val missingFallback = missing(i)
            if missingFallback == null then throw FormatViolationException.MissingColumn(i)
            else missingFallback.nn.missingFallback
          else
            val cell = row(i)
            if cell == null then
              val nullFallback = nulls(i)
              if nullFallback == null then throw FormatViolationException.UnexpectedNull(i)
              else nullFallback.nn.nullFallback
            else
              try decoders(i).decode(cell.nn)
              catch case NonFatal(e) => throw FieldDecodeException(i, cell.nn, e)
        i += 1
      end while
      if row.length > size then throw FormatViolationException.TooManyColumns(size, row.length)
      mir.fromProduct(Tuple.fromArray(result))
    end decodeRow
  end Impl

  /** Builds a row decoder for `T`.
    *
    * @param formatHasNullString
    *   whether the format defines a null string. Only then can a cell arrive as `null`, so only then is a [[NullFallback]] required per
    *   field.
    * @param allowRaggedRows
    *   whether rows shorter than the field count are tolerated. When true a [[MissingDataFallback]] is required for every field but the
    *   first; when false a short row fails at runtime.
    *
    * The first field is never missing. A record always has at least one cell: an empty line either does not reach us at all (the format
    * ignores empty lines) or arrives as a single empty cell, so only the fields after it can be absent.
    */
  inline def apply[T](inline formatHasNullString: Boolean, inline allowRaggedRows: Boolean)(using mir: ProductOf[T]): CsvRowDecoder[T] =
    type Fields = mir.MirroredElemTypes
    val missing: IArray[MissingDataFallback[Any] | Null] =
      inline if allowRaggedRows then summonMissingForTail[Fields] else noMissing[Fields]
    val nulls: IArray[NullFallback[Any] | Null] =
      inline if formatHasNullString then summonNulls[Fields] else noNulls[Fields]
    new Impl[T](summonDecoders[Fields], missing, nulls, mir)
  end apply

  /** Each typeclass is widened by subtyping while the field type is still known, so the tables need no cast. */
  private inline def summonDecoders[Fields <: Tuple]: IArray[CsvDecoder[Any]] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[CsvDecoder[Any]]
      case _: (h *: t)   =>
        val head: CsvDecoder[Any] = summonInline[CsvDecoder[h]]
        IArray.concat[CsvDecoder[Any]](IArray[CsvDecoder[Any]](head), summonDecoders[t])

  private inline def summonNulls[Fields <: Tuple]: IArray[NullFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[NullFallback[Any] | Null]
      case _: (h *: t)   =>
        val head: NullFallback[Any] = summonInline[NullFallback[h]]
        IArray.concat[NullFallback[Any] | Null](IArray[NullFallback[Any] | Null](head), summonNulls[t])

  /** A [[MissingDataFallback]] per field except the first, which is never missing. */
  private inline def summonMissingForTail[Fields <: Tuple]: IArray[MissingDataFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[MissingDataFallback[Any] | Null]
      case _: (_ *: t)   => IArray.concat[MissingDataFallback[Any] | Null](IArray[MissingDataFallback[Any] | Null](null), summonMissing[t])

  private inline def summonMissing[Fields <: Tuple]: IArray[MissingDataFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[MissingDataFallback[Any] | Null]
      case _: (h *: t)   =>
        val head: MissingDataFallback[Any] = summonInline[MissingDataFallback[h]]
        IArray.concat[MissingDataFallback[Any] | Null](IArray[MissingDataFallback[Any] | Null](head), summonMissing[t])

  /** One empty slot per field: the format cannot produce this case, so no typeclass is required.
    *
    * Two variants rather than one `IArray[Null]`: an array's component type is reified, so the slots have to be built at the element type
    * they will be read back at. A single `IArray[Null]` typechecks with `ClassTag.Null` but fails in the backend — `scala.runtime.Null$[]`
    * cannot be passed where `MissingDataFallback[]` is expected.
    */
  private inline def noMissing[Fields <: Tuple]: IArray[MissingDataFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[MissingDataFallback[Any] | Null]
      case _: (_ *: t)   => IArray.concat[MissingDataFallback[Any] | Null](IArray[MissingDataFallback[Any] | Null](null), noMissing[t])

  private inline def noNulls[Fields <: Tuple]: IArray[NullFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[NullFallback[Any] | Null]
      case _: (_ *: t)   => IArray.concat[NullFallback[Any] | Null](IArray[NullFallback[Any] | Null](null), noNulls[t])
end CsvRowDecoder
