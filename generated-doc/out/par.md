# Running computations in parallel

```scala
import ox.par

def computation1: Int =
  Thread.sleep(2000)
  1

def computation2: String =
  Thread.sleep(1000)
  "2"

val result: (Int, String) = par(computation1)(computation2)
// (1, "2")
```

If one of the computations fails, the other is interrupted, and `par` waits until both branches complete.
