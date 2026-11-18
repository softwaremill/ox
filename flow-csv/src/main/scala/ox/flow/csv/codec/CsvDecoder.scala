package ox.flow.csv.codec

/** Typeclass used to decode field string parsed form csv into [[T]] for decoding nulls see [[NullFallback]] for decoding missing data in ragged rows see
  * [[MissingDataFallback]]
  *
  * Intended to be used through a [[ox.flow.csv.format.Format]] and [[ox.flow.csv.parse]], which wrap and rethrow whatever a decoder throws
  * as a [[FieldDecodeException]].
 * @see [[MissingDataFallback]]
 * @see [[NullFallback]]
  */
trait CsvDecoder[+T]:
  def decode(from: String): T

object CsvDecoder:

  given CsvDecoder[String] with
    def decode(from: String): String = from

  given [T] => CsvDecoder[T] => CsvDecoder[Option[T]]:
    def decode(from: String): Option[T] = Option(summon[CsvDecoder[T]].decode(from))

  given [T] => CsvDecoder[T] => CsvDecoder[T | Null]:
    def decode(from: String): T | Null = summon[CsvDecoder[T]].decode(from)

  given CsvDecoder[Int] with
    def decode(from: String): Int = from.toInt

  given CsvDecoder[Long] with
    def decode(from: String): Long = from.toLong

  given CsvDecoder[Double] with
    def decode(from: String): Double = from.toDouble

  given CsvDecoder[Float] with
    def decode(from: String): Float = from.toFloat

  given CsvDecoder[Short] with
    def decode(from: String): Short = from.toShort

  given CsvDecoder[Byte] with
    def decode(from: String): Byte = from.toByte

  given CsvDecoder[Boolean] with
    def decode(from: String): Boolean = from.toBoolean

  given CsvDecoder[Char] with
    def decode(from: String): Char =
      if from.length == 1 then from.charAt(0)
      else throw IllegalArgumentException(s"Expected single character, got: '$from'")

  given CsvDecoder[BigDecimal] with
    def decode(from: String): BigDecimal = BigDecimal(from)

  given CsvDecoder[BigInt] with
    def decode(from: String): BigInt = BigInt(from)

  given CsvDecoder[java.util.UUID] with
    def decode(from: String): java.util.UUID = java.util.UUID.fromString(from)
end CsvDecoder
