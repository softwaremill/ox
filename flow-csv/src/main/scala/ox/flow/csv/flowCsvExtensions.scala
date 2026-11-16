package ox.flow.csv

import ox.flow.Flow
import ox.flow.csv.format.{ParserFormat, PrinterFormat}

extension [T](f: T)
  def print(fmt: PrinterFormat[T]): Flow[String] = fmt.writeData(f)

extension [T](f: Flow[String])
  def parse(fmt: ParserFormat[T]): T = fmt.parseData(f)

end extension
