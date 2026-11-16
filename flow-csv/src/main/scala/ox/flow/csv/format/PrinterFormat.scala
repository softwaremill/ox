package ox.flow.csv.format

import ox.flow.Flow

trait PrinterFormat[-Repr]:
  def writeData(data: Repr): Flow[String]
