package ox.flow.csv.codec.internal

import ox.flow.csv.codec.{CsvDecoder, MissingDataFallback, NullFallback}

import scala.annotation.publicInBinary
import scala.compiletime.*
import scala.deriving.Mirror.ProductOf

/** Decodes one CSV row into `T`.
  *
  * A row is `IArray[String | Null]`: Apache yields `null` for cells matching the format's null string, and a row shorter than the field
  * count means the trailing fields are missing. Both are resolved per field: missing -> [[MissingDataFallback]], null -> [[NullFallback]],
  * otherwise [[CsvDecoder]].
  */
private[csv] trait CsvRowDecoder[+T]:
  /** How many cells a full row has: the field count of `T`. */
  private[csv] def size: Int

  def decodeRow(row: IArray[String | Null]): T

@publicInBinary
private[csv] object CsvRowDecoder:
  private[csv] final class CsvDecodeException(message: String) extends RuntimeException(message)

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
            if missingFallback == null then throw CsvDecodeException(s"Missing column at index $i")
            else missingFallback.nn.missingFallback
          else
            val cell = row(i)
            if cell == null then
              val nullFallback = nulls(i)
              if nullFallback == null then throw CsvDecodeException(s"Unexpected null at column index $i")
              else nullFallback.nn.nullFallback
            else decoders(i).decode(cell.nn)
        i += 1
      end while
      if row.length > size then throw CsvDecodeException(s"Too many columns: expected $size, got ${row.length}")
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
    new Impl[T](
      summonDecoders[Fields],
      inline if allowRaggedRows then summonMissingForTail[Fields] else noMissing[Fields],
      inline if formatHasNullString then summonNulls[Fields] else noNulls[Fields],
      mir
    )
  end apply

  /** Each typeclass is widened by subtyping while the field type is still known, so the tables need no cast. */
  private inline def summonDecoders[Fields <: Tuple]: IArray[CsvDecoder[Any]] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[CsvDecoder[Any]]
      case _: (h *: t)   =>
        val head: CsvDecoder[Any] = summonInline[CsvDecoder[h]]
        head +: summonDecoders[t]

  private inline def summonNulls[Fields <: Tuple]: IArray[NullFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[NullFallback[Any] | Null]
      case _: (h *: t)   =>
        val head: NullFallback[Any] = summonInline[NullFallback[h]]
        val tail: IArray[NullFallback[Any] | Null] = summonNulls[t]
        head +: tail

  /** A [[MissingDataFallback]] per field except the first, which is never missing. */
  private inline def summonMissingForTail[Fields <: Tuple]: IArray[MissingDataFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[MissingDataFallback[Any] | Null]
      case _: (_ *: t)   => (null: MissingDataFallback[Any] | Null) +: summonMissing[t]

  private inline def summonMissing[Fields <: Tuple]: IArray[MissingDataFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[MissingDataFallback[Any] | Null]
      case _: (h *: t)   =>
        val head: MissingDataFallback[Any] = summonInline[MissingDataFallback[h]]
        // ascribed to pin the element type: `+:` would otherwise infer it from `head`, which under `-Yexplicit-nulls` is narrower than the
        // declared element type
        val tail: IArray[MissingDataFallback[Any] | Null] = summonMissing[t]
        head +: tail

  /** One empty slot per field: the format cannot produce this case, so no typeclass is required.
    *
    * Two variants rather than one `IArray[Null]`: an array's component type is reified, so the slots have to be built at the element type
    * they will be read back at. A single `IArray[Null]` typechecks with `ClassTag.Null` but fails in the backend — `scala.runtime.Null$[]`
    * cannot be passed where `MissingDataFallback[]` is expected.
    */
  private inline def noMissing[Fields <: Tuple]: IArray[MissingDataFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[MissingDataFallback[Any] | Null]
      case _: (_ *: t)   => (null: MissingDataFallback[Any] | Null) +: noMissing[t]

  private inline def noNulls[Fields <: Tuple]: IArray[NullFallback[Any] | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[NullFallback[Any] | Null]
      case _: (_ *: t)   => (null: NullFallback[Any] | Null) +: noNulls[t]
end CsvRowDecoder
