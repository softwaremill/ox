package ox.flow.csv.codec

/** Typeclass used to encode T into String for encoding nulls see [[CsvEncoderWithNull]]
  */
trait CsvEncoder[-T]:
  def encode(value: T): String

object CsvEncoder:

  given CsvEncoder[String] with
    def encode(value: String): String = value

  given CsvEncoder[Int] with
    def encode(value: Int): String = value.toString

  given CsvEncoder[Long] with
    def encode(value: Long): String = value.toString

  given CsvEncoder[Double] with
    def encode(value: Double): String = value.toString

  given CsvEncoder[Float] with
    def encode(value: Float): String = value.toString

  given CsvEncoder[Short] with
    def encode(value: Short): String = value.toString

  given CsvEncoder[Byte] with
    def encode(value: Byte): String = value.toString

  given CsvEncoder[Boolean] with
    def encode(value: Boolean): String = value.toString

  given CsvEncoder[Char] with
    def encode(value: Char): String = value.toString

  given CsvEncoder[BigDecimal] with
    def encode(value: BigDecimal): String = value.toString

  given CsvEncoder[BigInt] with
    def encode(value: BigInt): String = value.toString

  given CsvEncoder[java.util.UUID] with
    def encode(value: java.util.UUID): String = value.toString
end CsvEncoder

/** Same as [[CsvEncoder]] but allows emitting [[null]] to parsing backend which will be represented as nullString in output csv, it should
  * also handle [[T]] encoding
  *
  * Emitting [[null]] for Format that doesn't specify nullString is not safe and can lead to unexpected behavior of printed csv (for example
  * failing to roundtrip for same format) Thus instances of this class are only summoned formats that specify nullString, otherwise
  * [[CsvEncoder]] is summoned.
  */
trait CsvEncoderWithNull[-T]:
  def encode(value: T): String | Null

object CsvEncoderWithNull:

  given [T] => CsvEncoder[T] => CsvEncoderWithNull[Option[T]]:
    def encode(value: Option[T]): String | Null = value.map(summon[CsvEncoder[T]].encode).orNull

  given [T] => CsvEncoder[T] => CsvEncoderWithNull[T | Null]:
    def encode(value: T | Null): String | Null = if value == null then null else summon[CsvEncoder[T]].encode(value.nn)
