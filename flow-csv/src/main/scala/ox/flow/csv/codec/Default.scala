package ox.flow.csv.codec

/** Primitive default instances for [[MissingDataFallback]] and [[NullFallback]].
  *
  * Import all:          `import ox.flow.csv.codec.defaults.all.given`
  * Import by typeclass: `import ox.flow.csv.codec.defaults.missingData.given`
  *                      `import ox.flow.csv.codec.defaults.nullData.given` */
object defaults:

  object missingDataFallback:
    given MissingDataFallback[String] with
      override def missingFallback: String = ""
    given MissingDataFallback[Int] with
      override def missingFallback: Int = 0
    given MissingDataFallback[Long] with
      override def missingFallback: Long = 0L
    given MissingDataFallback[Double] with
      override def missingFallback: Double = 0.0
    given MissingDataFallback[Float] with
      override def missingFallback: Float = 0.0f
    given MissingDataFallback[Short] with
      override def missingFallback: Short = 0
    given MissingDataFallback[Byte] with
      override def missingFallback: Byte = 0
    given MissingDataFallback[Boolean] with
      override def missingFallback: Boolean = false
    given MissingDataFallback[BigDecimal] with
      override def missingFallback: BigDecimal = BigDecimal(0)
    given MissingDataFallback[BigInt] with
      override def missingFallback: BigInt = BigInt(0)

  object nullFallback:
    given NullFallback[String] with
      override def nullFallback: String = ""
    given NullFallback[Int] with
      override def nullFallback: Int = 0
    given NullFallback[Long] with
      override def nullFallback: Long = 0L
    given NullFallback[Double] with
      override def nullFallback: Double = 0.0
    given NullFallback[Float] with
      override def nullFallback: Float = 0.0f
    given NullFallback[Short] with
      override def nullFallback: Short = 0
    given NullFallback[Byte] with
      override def nullFallback: Byte = 0
    given NullFallback[Boolean] with
      override def nullFallback: Boolean = false
    given NullFallback[BigDecimal] with
      override def nullFallback: BigDecimal = BigDecimal(0)
    given NullFallback[BigInt] with
      override def nullFallback: BigInt = BigInt(0)

  object all:
    export missingDataFallback.given
    export nullFallback.given
