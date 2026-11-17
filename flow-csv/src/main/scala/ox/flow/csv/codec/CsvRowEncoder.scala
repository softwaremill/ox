package ox.flow.csv.codec

import scala.compiletime.*
import scala.deriving.Mirror.ProductOf

/** Encodes `T` into one CSV row. `null` cells are written as the format's null string. */
private[csv] trait CsvRowEncoder[-T]:
  def encodeRow(value: T): Array[String | Null]

private[csv] object CsvRowEncoder:

  private[csv] final class Impl[T](encoders: IArray[Any => String | Null], size: Int) extends CsvRowEncoder[T]:
    def encodeRow(value: T): Array[String | Null] =
      val product = value.asInstanceOf[Product]
      val result = new Array[String | Null](size)
      var i = 0
      while i < size do
        result(i) = encoders(i)(product.productElement(i))
        i += 1
      result

  /** For a format with no null string, where a `null` cell cannot round-trip.
    *
    * Only a hand-written [[CsvEncoder]] for a nullable type (`CsvEncoder[MyType | Null]`) can reach this,
    * which is a legitimate choice — rendering a null as `"-"`, say — so it warns rather than fails, once per
    * field position.
    */
  private[csv] final class NullWarningImpl[T](encoders: IArray[Any => String | Null], size: Int, typeName: String)
      extends CsvRowEncoder[T]:
    private val warned = new java.util.concurrent.atomic.AtomicBoolean(false)

    def encodeRow(value: T): Array[String | Null] =
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
      result

  /** Builds a row encoder for `T`.
    *
    * @param formatHasNullString whether the format defines a null string. Only then can a cell be written as
    *                            `null`, so only then is a [[CsvEncoderWithNull]] summoned. Writing a `null`
    *                            into a format that has no null string would not round-trip, so for such a
    *                            format every field must reach a cell through a plain [[CsvEncoder]].
    */
  inline def apply[T](inline formatHasNullString: Boolean)(using mir: ProductOf[T]): CsvRowEncoder[T] =
    type Fields = mir.MirroredElemTypes
    inline if formatHasNullString then
      val encoders = summonEncodersWithNull[Fields]
      new Impl[T](encoders, encoders.length)
    else
      val encoders = summonEncoders[Fields]
      new NullWarningImpl[T](encoders, encoders.length, constValue[mir.MirroredLabel])

  /** Per field a [[CsvEncoderWithNull]]: the format can represent a null, so a field type that may produce
    * one must say how. Each is adapted to a function here, while the field type is still known, so encoding
    * a row needs no runtime type test. */
  private inline def summonEncodersWithNull[Fields <: Tuple]: IArray[Any => String | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[Any => String | Null]
      case _: (h *: t) =>
        val head: Any => String | Null =
          val e = summonInline[CsvEncoderWithNull[h]]
          v => e.encode(v.asInstanceOf[h])
        head +: summonEncodersWithNull[t]

  /** Per field a plain [[CsvEncoder]]: the format has no null string, so no cell may be written as `null`. */
  private inline def summonEncoders[Fields <: Tuple]: IArray[Any => String | Null] =
    inline erasedValue[Fields] match
      case _: EmptyTuple => IArray.empty[Any => String | Null]
      case _: (h *: t) =>
        val head: Any => String | Null =
          val e = summonInline[CsvEncoder[h]]
          v => e.encode(v.asInstanceOf[h])
        head +: summonEncoders[t]
