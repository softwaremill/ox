# 1. Error propagation in channels

Date: 2023-10-30

## Context

What should happen when an error is encountered when processing channel elements? Should it be propagated downstream or re-thrown?

## Decision

We chose to only propagate the errors downstream, so that they are eventually thrown when the source is discharged.

Won't this design cause upstream channels / sources to operate despite the consumer being gone (because of the 
exception)? 

It depends on two factors:
- whether there are any forks running in parallel to the failed one,
- whether you only signal the exception downstream, or also choose to re-throw it.

If there's only a single fork running at a time, it would terminate processing anyway, so it's enough to signal the exception to the downstream.

If there are multiple forks running in parallel, there are two possible scenarios:
1. If you choose to re-throw the exception, it should cause the containing scope to finish (or a supervised fork to fail), 
cancelling any forks that are operating in the background. Any unused channels can then be garbage-collected.
2. If you choose not to re-throw, the forks running in parallel would be allowed to complete normally (unless the containing scope is finished for another reason).

Internally, for the built-in `Source` operators, we took the latter approach, i.e. we chose not to re-throw and let the parallel forks complete normally. 
However, we keep in mind that they might not be able to send to downstream channel anymore - since the downstream might already be closed by the failing fork.

### Example

Let's have a look at the error handling in `Source.mapParUnordered` to demonstrate our approach. This operator applies a mapping function to a given number of elements in parallel, and is implemented as follows:

```scala
def mapParUnordered[U](parallelism: Int)(f: T => U)(using Ox, StageCapacity): Source[U] =
  val c = StageCapacity.newChannel[U]
  val s = new Semaphore(parallelism)
  forkDaemon {
    supervised {                                          // (1)
      repeatWhile {                                       
        s.acquire()
        receive() match
          case ChannelClosed.Done => false
          case ChannelClosed.Error(r) =>                  // (2)
            c.error(r)
            false
          case t: T @unchecked =>
            fork {                                        // (3)
              try
                c.send(f(t))                              // (4)
                s.release()
              catch case e: Exception => c.error(t)       // (5)
            }
            true
      }
    }
    c.done()
  }
  c
```

It first creates a `supervised` scope (1), i.e. one that only completes (on the happy path) when all 
non-daemon supervised forks complete. The mapping function `f` is then run in parallel using non-daemon `fork`s (3).

Let's assume an input `Source` with 4 elements, and `parallelism` set to 2:

```scala
val input: Source[Int] = Source.fromValues(1, 2, 3, 4)
def f(i: Int): Int = if ()

val result: Source[Int] = input.mapParUnordered(2)(f)
```

Let's also assume that the mapping function `f` is an identity with a fixed delay, but it's going to fail 
immediately (by throwing an exception) when it processes the third element.

In this scenario, the first 2-element batch would successfully process elements `1` and `2`, and emit them 
downstream (i.e. to the `result` source). Then the forks processing of `3` and `4` would start in parallel. 
While `4` would still be processed (due to the delay in `f`), the fork processing `3` would immediately 
throw an exception, which would be caught (5). Consequently, the downstream channel `c` would be closed 
with an error, but the fork processing `4` would remain running. Whenever the fork processing `4` is done 
executing `f`, its attempt to `c.send` (4) will fail silently - due to `c` being already closed. 
Eventually, no results from the second batch would be send downstream.

The sequence of events would be similar if it was the upstream (rather than `f`) that failed, i.e. when `receive()` resulted in an error (2).