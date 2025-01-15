# Bulkhead

The bulkhead mechanism allows to constaint number of in flight operations made on one instance. For example to allow for maximum of 3 operation running at the same time.

# API

Bulkhead uses semaphore to determine if operation should be run, it exposes two methods:
- `def runOrDrop[T](operation: => T): Option[T]`
- `def runOrDropWithTimeout[T](timeoutDuration: FiniteDuration)(operation: => T): Option[T]`

## Operation definition

The `operation` can be provided directly using a by-name parameter, i.e. `f: => T`.
Since bulkhead does not need to handle errors there is no need for [ErrorMode](../basics/error-handling.md).

## Examples

```scala mdoc:compile-only
import scala.concurrent.duration.*
import ox.*
import ox.resilience.Bulkhead

val bulkHead = Bulkhead(1)
def f() =
  sleep(2000.millis)
  "result"

var result1: Option[String] = None
var result2: Option[String] = None
var result3: Option[String] = None
var result4: Option[String] = None

supervised:
  forkUserDiscard:
    result1 = bulkHead.runOrDrop(f())
  forkUserDiscard:
    sleep(500.millis)
    result2 = bulkHead.runOrDrop(f())
  forkUserDiscard:
    sleep(1000.millis)
    result3 = bulkHead.runOrDropWithTimeout(2000.millis)(f())
  forkUserDiscard:
    sleep(1000.millis)
    result4 = bulkHead.runOrDropWithTimeout(500.millis)(f())

result1 // Some("result") - completed first
result2 // None - exceeded maxConcurrentCalls, so the operation was dropped
result3 // Some("result") - completed after waiting for 1000 millis
result4 // None - timed out
```