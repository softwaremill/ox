package ox.flow.csv.format

import ox.flow.Flow

trait ParserFormat[+Repr]:
  def parseData(data: Flow[String]): Repr
