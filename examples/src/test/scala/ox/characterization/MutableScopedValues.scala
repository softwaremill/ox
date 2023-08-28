package ox

import java.util.concurrent.{Callable, StructuredTaskScope}
import java.util.concurrent.atomic.AtomicReference

@main def mutableScopedValues(): Unit =
  val trail = ScopedValue.newInstance[AtomicReference[List[String]]]()
  val result = ScopedValue.callWhere(
    trail,
    new AtomicReference(Nil),
    { () =>
      trail.get.updateAndGet("A" :: _)

//      val c = Thread.startVirtualThread { () =>
//        println("2: " + trail.get.updateAndGet("B" :: _))
//      }
//      c.join()

      val scope1 = new StructuredTaskScope.ShutdownOnFailure()
      try
        scope1.fork(() => trail.get.updateAndGet("B" :: _))
        scope1.join()
      finally scope1.close()

      trail.get.get()
    }: Callable[List[String]]
  )

  println(s"Result: $result")
