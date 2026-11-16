package ox.flow.csv.format
type Format[Repr] = ParserFormat[Repr] & PrinterFormat[Repr]
