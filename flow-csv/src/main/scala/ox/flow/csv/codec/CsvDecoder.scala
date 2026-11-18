package ox.flow.csv.codec

/** Typeclass used to decode string parsed form csv into T for decoding nulls see [[NullFallback]] for decoding missing see
  * [[MissingDataFallback]]
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

  /** @throws java.lang.NumberFormatException If the string does not contain a parsable `Int`. */
  given CsvDecoder[Int] with
    def decode(from: String): Int = from.toInt

  /** @throws java.lang.NumberFormatException If the string does not contain a parsable `Long`. */
  given CsvDecoder[Long] with
    def decode(from: String): Long = from.toLong

  /** @throws java.lang.NumberFormatException If the string does not contain a parsable `Double`. */
  given CsvDecoder[Double] with
    def decode(from: String): Double = from.toDouble

  /** @throws java.lang.NumberFormatException If the string does not contain a parsable `Float`. */
  given CsvDecoder[Float] with
    def decode(from: String): Float = from.toFloat

  /** @throws java.lang.NumberFormatException If the string does not contain a parsable `Short`. */
  given CsvDecoder[Short] with
    def decode(from: String): Short = from.toShort

  /** @throws java.lang.NumberFormatException If the string does not contain a parsable `Byte`. */
  given CsvDecoder[Byte] with
    def decode(from: String): Byte = from.toByte

  /** @throws java.lang.IllegalArgumentException If the string does not contain a parsable `Boolean`. */
  given CsvDecoder[Boolean] with
    def decode(from: String): Boolean = from.toBoolean

  /** @throws java.lang.IllegalArgumentException If the string is not exactly one character long. */
  given CsvDecoder[Char] with
    def decode(from: String): Char =
      if from.length == 1 then from.charAt(0)
      else throw IllegalArgumentException(s"Expected single character, got: '$from'")

  /** @throws java.lang.NumberFormatException if the value is not a valid representation of a `BigDecimal`. */
  given CsvDecoder[BigDecimal] with
    def decode(from: String): BigDecimal = BigDecimal(from)

  /** @throws java.lang.NumberFormatException if the value is not a valid representation of a `BigInt`. */
  given CsvDecoder[BigInt] with
    def decode(from: String): BigInt = BigInt(from)

  /** @throws java.lang.IllegalArgumentException If the value does not conform to the UUID string representation. */
  given CsvDecoder[java.util.UUID] with
    def decode(from: String): java.util.UUID = java.util.UUID.fromString(from)
end CsvDecoder
