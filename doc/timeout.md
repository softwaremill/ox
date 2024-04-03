# Timeout a computation

```scala
import ox.timeout
import scala.concurrent.duration.DurationInt

def computation: Int =
  sleep(2.seconds)
  1

val result1: Try[Int] = Try(timeout(1.second)(computation)) // failure: TimeoutException
val result2: Try[Int] = Try(timeout(3.seconds)(computation)) // success: 1
```

A variant, `timeoutOption`, doesn't throw a `TimeoutException` on timeout, but returns `None` instead.
