package ox.flow.csv.format

import ox.Chunk
import ox.flow.Flow

trait ParserFormat[+Repr]:
  def parseData(data: Flow[Chunk[String]]): Repr



