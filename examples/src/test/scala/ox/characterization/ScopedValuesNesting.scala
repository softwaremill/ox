package ox.characterization

import ox.discard

import java.util.concurrent.StructuredTaskScope

@main def scopedValuesNesting1(): Unit =
  val v = ScopedValue.newInstance[String]()
  ScopedValue
    .callWhere(
      v,
      "x",
      () =>
        val scope1 = new StructuredTaskScope.ShutdownOnFailure()
        try
          scope1.fork(() => println(v.get()))
          scope1.join()
        finally scope1.close()
    )
    .discard
end scopedValuesNesting1

@main def scopedValuesNesting2(): Unit =
  val v = ScopedValue.newInstance[String]()
  val scope1 = new StructuredTaskScope.ShutdownOnFailure()
  ScopedValue
    .callWhere(
      v,
      "x",
      () =>
        try
          scope1.fork(() => println(v.get()))
          scope1.join()
        finally scope1.close()
    )
    .discard
end scopedValuesNesting2
