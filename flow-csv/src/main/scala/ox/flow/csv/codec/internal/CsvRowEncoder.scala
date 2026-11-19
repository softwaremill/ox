package ox.flow.csv.codec.internal

import ox.flow.csv.codec.{CsvEncoder, CsvEncoderWithNull}
import ox.flow.csv.format.internal.macros

import scala.annotation.publicInBinary
import scala.compiletime.*
import scala.deriving.Mirror.ProductOf

/** Encodes `T` into one CSV row. `null` cells are written as the format's null string. */
private[csv] trait CsvRowEncoder[-T]:
  def encodeRow(value: T): IArray[String | Null]

@publicInBinary
private[csv] object CsvRowEncoder:

  //explicit type to get over operator precedence
  private[csv] type FieldEncoder = Function1[Any, String | Null]

  private[csv] final class Impl[T](encoders: IArray[FieldEncoder], size: Int) extends CsvRowEncoder[T]:
    def encodeRow(value: T): IArray[String | Null] =
      val product = value.asInstanceOf[Product]
      val result = new Array[String | Null](size)
      var i = 0
      while i < size do
        result(i) = encoders(i)(product.productElement(i))
        i += 1
      IArray.unsafeFromArray(result)
    end encodeRow
  end Impl

  /** For a format with no null string, where a `null` cell cannot round-trip.
    *
    * Only a hand-written [[CsvEncoder]] for a nullable type (`CsvEncoder[MyType | Null]`) can reach this, which is a legitimate choice —
    * rendering a null as `"-"`, say — so it warns rather than fails, once per field position.
    */
  private[csv] final class NullWarningImpl[T](encoders: IArray[FieldEncoder], size: Int, typeName: String) extends CsvRowEncoder[T]:
    private val warned = new java.util.concurrent.atomic.AtomicBoolean(false)

    def encodeRow(value: T): IArray[String | Null] =
      val product = value.asInstanceOf[Product]
      val result = new Array[String | Null](size)
      var i = 0
      while i < size do
        val cell = encoders(i)(product.productElement(i))
        if cell == null && !warned.get() && warned.compareAndSet(false, true) then
          Console.err.println(
            s"[flow-csv] $typeName field $i encoded to null, but this format defines no null string. " +
              "The cell is written as empty and may not be read properly by this format. " +
              "If your intention was to write null, set a null string for your format and provide a CsvEncoderWithNull."
          )
        result(i) = cell
        i += 1
      end while
      IArray.unsafeFromArray(result)
    end encodeRow
  end NullWarningImpl

  /** Builds a row encoder for `T`.
    *
    * @param formatHasNullString
    *   whether the format defines a null string. Only then can a cell be written as `null`, so only then is a [[CsvEncoderWithNull]]
    *   summoned. Writing a `null` into a format that has no null string would not round-trip, so for such a format every field must reach a
    *   cell through a plain [[CsvEncoder]].
    */
  inline def apply[T](inline formatHasNullString: Boolean)(using mir: ProductOf[T]): CsvRowEncoder[T] =
    type Fields = mir.MirroredElemTypes
    inline if formatHasNullString then
      val encoders = summonEncodersWithNull[Fields]
      new Impl[T](encoders, encoders.length)
    else
      val encoders = summonEncoders[Fields]
      new NullWarningImpl[T](encoders, encoders.length, constValue[mir.MirroredLabel])
  end apply

  /** Per field a [[CsvEncoderWithNull]]: the format can represent a null, so a field type that may produce one must say how. Each is
    * adapted to a function here, while the field type is still known, so encoding a row needs no runtime type test.
    */
  private inline def summonEncodersWithNull[Fields <: Tuple]: IArray[FieldEncoder] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[FieldEncoder]
      case _: (h *: t)   =>
        val head: FieldEncoder =
          val e = summonInline[CsvEncoderWithNull[h]]
          v => e.encode(v.asInstanceOf[h])
        IArray.concat[FieldEncoder](IArray[FieldEncoder](head), summonEncodersWithNull[t])

  /** Per field a plain [[CsvEncoder]]: the format has no null string, so no cell may be written as `null`.
    *
    * An `Option` field reaching this branch is the common mistake, and the stock "no given instance of type
    * `CsvEncoder[Option[X]]`" says neither why `Option` is unsupported here nor what to do about it. The summon is therefore attempted
    * first and only its failure is enriched: a user-provided `CsvEncoder[Option[X]]` is a legitimate choice - rendering `None` as `"-"`,
    * say - and keeps working untouched.
    */
  private inline def summonEncoders[Fields <: Tuple]: IArray[FieldEncoder] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[FieldEncoder]
      case _: (h *: t)   =>
        val head: FieldEncoder =
          val e = encoderFor[h]
          v => e.encode(v.asInstanceOf[h])
        // `+:` infers the result's element type from `head`, and under `-Yexplicit-nulls` picks one narrower than the declared element
        // type, so the tail is ascribed to pin it
        IArray.concat[FieldEncoder](IArray[FieldEncoder](head), summonEncoders[t])

  /** The [[CsvEncoder]] for one field.
    *
    * Delegates to [[macros.summonEncoderOrExplain]] so that a failure for an `Option` field keeps the compiler's own message and gains a
    * note under it, rather than being replaced by one.
    */
  private inline def encoderFor[H]: CsvEncoder[H] = macros.summonEncoderOrExplain[H]
end CsvRowEncoder
