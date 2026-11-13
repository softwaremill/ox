package ox.flow.csv

import ox.flow.Flow
import ox.flow.csv.format.{ParserFormat, PrinterFormat}
import ox.Chunk

extension [T](f: T)
  def print(fmt: PrinterFormat[T]): Flow[Chunk[String]] = fmt.writeData(f)

extension [T](f: Flow[Chunk[String]])
  def parse(fmt: ParserFormat[T]):T = fmt.parseData(f)

end extension
