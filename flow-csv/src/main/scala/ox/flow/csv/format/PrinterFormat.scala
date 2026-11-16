package ox.flow.csv.format

import ox.Chunk
import ox.flow.Flow

trait PrinterFormat[-Repr]:
  def writeData(data: Repr): Flow[Chunk[String]]