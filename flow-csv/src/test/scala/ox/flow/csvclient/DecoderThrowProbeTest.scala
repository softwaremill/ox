package ox.flow.csvclient

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DecoderThrowProbeTest extends AnyFlatSpec with Matchers:

  private def probe(label: String)(f: => Any): Unit =
    try
      val r = f
      info(f"$label%-30s => OK: $r")
    catch case e: Throwable => info(f"$label%-30s => ${e.getClass.getName}: ${e.getMessage}")

  "primitive conversions" should "reveal thrown exception types" in:
    probe("\"x\".toInt")("x".toInt)
    probe("\"\".toInt")("".toInt)
    probe("\"x\".toLong")("x".toLong)
    probe("\"x\".toDouble")("x".toDouble)
    probe("\"\".toDouble")("".toDouble)
    probe("\"x\".toFloat")("x".toFloat)
    probe("\"x\".toShort")("x".toShort)
    probe("\"99999\".toShort")("99999".toShort)
    probe("\"999\".toByte")("999".toByte)
    probe("\"x\".toBoolean")("x".toBoolean)
    probe("\"\".toBoolean")("".toBoolean)
    probe("\"TRUE\".toBoolean")("TRUE".toBoolean)

  "big numbers and uuid" should "reveal thrown exception types" in:
    probe("BigDecimal(\"x\")")(BigDecimal("x"))
    probe("BigDecimal(\"\")")(BigDecimal(""))
    probe("BigInt(\"x\")")(BigInt("x"))
    probe("BigInt(\"\")")(BigInt(""))
    probe("UUID.fromString(\"x\")")(java.util.UUID.fromString("x"))
    probe("UUID.fromString(\"\")")(java.util.UUID.fromString(""))
    probe("UUID.fromString(valid)")(java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))

end DecoderThrowProbeTest
