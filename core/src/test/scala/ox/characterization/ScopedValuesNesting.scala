package ox.characterization

import jdk.incubator.concurrent.{ScopedValue, StructuredTaskScope}

@main def scopedValuesNesting1(): Unit =
  val v = ScopedValue.newInstance[String]()
  ScopedValue.where(
    v,
    "x",
    () => {
      val scope1 = new StructuredTaskScope.ShutdownOnFailure()
      try
        scope1.fork(() => println(v.get()))
        scope1.join()
      finally scope1.close()
    }
  )

@main def scopedValuesNesting2(): Unit =
  val v = ScopedValue.newInstance[String]()
  val scope1 = new StructuredTaskScope.ShutdownOnFailure()
  ScopedValue.where(
    v,
    "x",
    () => {
      try
        scope1.fork(() => println(v.get()))
        scope1.join()
      finally scope1.close()
    }
  )
