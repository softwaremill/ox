package ox.flow.csv.format.compat

import org.apache.commons.csv.CSVFormat.Builder as ApacheCSVBuilder
import org.apache.commons.csv.{DuplicateHeaderMode, CSVFormat as ApacheCSVFormat}
import ox.flow.csv.format.{HeaderMode, QuoteMode, Settings}

private[csv] object ApacheCsvFormatCompat:
  /** Brings every Apache setting that is not part of our contract to a fixed value.
    *
    * Header handling is relaxed so that the parser never consumes, validates or rejects the first record: every record
    * reaches us untouched and [[HeaderMode]] alone decides what the first one is.
    *
    * The remaining settings are ones we do not expose at all
   *   pinning them keeps them in order to have consistent `equals`/`hashCode`.
    */
  private [csv] def overrideSettingsForCompatibility(apacheBuilder: ApacheCSVBuilder): ApacheCSVBuilder =
    apacheBuilder
      .setHeader(null.asInstanceOf[Array[String]]*) //ok - header is represented as CSVHeader
      .setSkipHeaderRecord(false)// ok - we use our HeaderMode
      .setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL) //ok predefined formats always have allow all, and this only is taken into account when setHeader was called
      .setAllowMissingColumnNames(true) //ok predefined formats always have allow all, and this only is taken into account when setHeader was called
      .setHeaderComments(null.asInstanceOf[Array[String]]*) //todo - ok, we can just add an CommentRecord before any row
      .setIgnoreHeaderCase(false) //access only //todo ok
      .setAutoFlush(false) //not needed
      .setMaxRows(0) //not needed - flows is lazy so user can take(maxRows)
      // empty lines must reach us as records, so that they can be represented on the data level
//      .setIgnoreEmptyLines(false) //todo - shouldnt be here, format define, we should allow users setting itpredefined formats should carry this unaffected because of compatibility with targets like excel

    //factors influencing output shape/type
    //HeaderMode.Force (introduced by us) - forces first line as header //todo influencing output shape, true = no header in output, only data
  //HeaderMode.TryParseAsData (introduced by us) - optional header //todo influencing output shape, true = no header in output, only data
  //HeaderMode.matching (introduced by us) - optional header //todo influencing output shape, true = no header in output, only data

  /** Applies the settings to a newly created [[ApacheCSVBuilder]], which validates them as the format is constructed. */
  @throws[IllegalArgumentException]
  private[csv] def toApache(settings: Settings): ApacheCSVFormat =
    val b = ApacheCSVBuilder.create()
    b.setCommentMarker(settings.commentMarker.map(Character.valueOf).orNull)
    b.setDelimiter(settings.delimiter)
    b.setEscape(settings.escape.map(Character.valueOf).orNull)
    b.setIgnoreSurroundingSpaces(settings.ignoreSurroundingSpaces)
    b.setLenientEof(settings.lenientEof)
    b.setNullString(settings.nullString.orNull)
    b.setQuote(settings.quote.map(Character.valueOf).orNull)
    b.setQuoteMode(settings.quoteMode.map(_.toApache).orNull)
    b.setRecordSeparator(settings.recordSeparator)
    b.setTrailingData(settings.trailingData)
    b.setTrailingDelimiter(settings.trailingDelimiter)
    b.setTrim(settings.trim)

    overrideSettingsForCompatibility(b).get


  /** Reads the settings out of a predefined Apache format, relaxing the ones we take over. */
  private[csv] def settingsOf(format: ApacheCSVFormat, headerMode: HeaderMode): Settings =
    val relaxed = overrideSettingsForCompatibility(format.builder()).get()
    Settings(
      headerMode = headerMode,
      commentMarker = Option(relaxed.getCommentMarker).map(_.charValue),
      delimiter = relaxed.getDelimiterString,
      escape = Option(relaxed.getEscapeCharacter).map(_.charValue),
      ignoreSurroundingSpaces = relaxed.getIgnoreSurroundingSpaces,
      lenientEof = relaxed.getLenientEof,
      nullString = Option(relaxed.getNullString),
      quote = Option(relaxed.getQuoteCharacter).map(_.charValue),
      quoteMode = Option(relaxed.getQuoteMode).map(QuoteMode.fromApache),
      recordSeparator = relaxed.getRecordSeparator,
      trailingData = relaxed.getTrailingData,
      trailingDelimiter = relaxed.getTrailingDelimiter,
      trim = relaxed.getTrim
    )
