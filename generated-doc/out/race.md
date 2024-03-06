# Race two computations

```scala
import ox.race

def computation1: Int =
  Thread.sleep(2000)
1

def computation2: Int =
  Thread.sleep(1000)
2

val result: Int = race(computation1)(computation2)
// 2
```

The losing computation is interrupted using `Thread.interrupt`. `raceSuccess` waits until both branches finish; this
also applies to the losing one, which might take a while to clean up after interruption.

## Error handling

* `raceSuccess` returns the first result, or re-throws the last exception
* `raceResult` returns the first result, or re-throws the first exception
