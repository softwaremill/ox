package ox.flow.csv.format

transparent sealed trait HeaderMode //transparent to prevent eager widening, will be needed for [[Builder]] dsl

object HeaderMode:

  /** Always treat first row as header, even if it would be possible to parse it as data, also always require header for printer
    */
  case object Force extends HeaderMode
  type Force = Force.type

  /** Attempts matching first row against [[matching]] according to [[ignoreHeaderCase]], if matched, first row is treated as header,
    * otherwise as data.
    */
  case class MatchWith(matching: Array[String], ignoreHeaderCase: Boolean) extends HeaderMode

  /** Attempts parsing first row as data, if parsing fails first row is assumed to be a header, if parsing succeeds first record is treated
    * as data row
    */
  case object TryParseAsData extends HeaderMode
  type TryParseAsData = TryParseAsData.type
end HeaderMode
