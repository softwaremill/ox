# Fork & join threads

It's safest to use higher-level methods, such as `par` or `race`, however this isn't always sufficient. For
these cases, threads can be started using the structured concurrency APIs described below.

Forks (new threads) can only be started within a **concurrency scope**. Such a scope is defined using the `supervised`,
`supervisedError` or `unsupervised` methods.

The lifetime of the forks is defined by the structure of the code, and corresponds to the enclosing `supervised`, 
`supervisedError` or `unsupervised` block. Once the code block passed to the scope completes, any forks that are still 
running are interrupted. The whole block will complete only once all forks have completed (successfully, or with an 
exception).

Hence, it is guaranteed that all forks started within `supervised`, `supervisedError` or `unsupervised` will finish 
successfully, with an exception, or due to an interrupt.

For example, the code below is equivalent to `par`:

```scala
import ox.{fork, sleep, supervised}
import scala.concurrent.duration.*

supervised {
  val f1 = fork {
    sleep(2.seconds)
    1
  }

  val f2 = fork {
    sleep(1.second)
    2
  }

  (f1.join(), f2.join())
}
```

It is a compile-time error to use any of the `fork` methods outside of a `supervised` block. Helper methods might 
require to be run within a scope by requiring the `Ox` capability:

```scala
import ox.{fork, Fork, Ox, sleep, supervised}
import scala.concurrent.duration.*

def forkComputation(p: Int)(using Ox): Fork[Int] = fork {
  sleep(p.seconds)
  p + 1
}

supervised {
  val f1 = forkComputation(2)
  val f2 = forkComputation(4)
  (f1.join(), f2.join())
}
```

Scopes can be arbitrarily nested.

## Types of forks - summary

* `fork`: supervised, daemon fork
* `forkUser`: supervised, user fork (scope will wait for it to complete, if there are no other errors)
* `forkError`: supervised, daemon fork, which is allowed to fail with an application error
* `forkUserError`: supervised, user fork, which is allowed to fail with an application error
* `forkPlain`: unsupervised fork
* `forkCancellable`: unsupervised, cancellable fork

## Supervision

The default scope, created with `supervised`, watches over the forks that are started within. Any forks started with
`fork`, `forkUser`, `forkError` and `forUserError` are by default supervised.

This means that the scope will end only when either:

* all (user, supervised) forks, including the body passed to `supervised`, succeed
* or any (supervised) fork, including the body passed to `supervised`, fails

Hence an exception in any of the forks will cause the whole scope to end. Ending the scope means that all running forks
are cancelled (using interruption). Once all forks complete, the exception is propagated further, that is re-thrown by
the `supervised` method invocation:

```scala
import ox.{fork, forkUser, Ox, sleep, supervised}
import scala.concurrent.duration.*

supervised {
  forkUser {
    sleep(1.second)
    println("Hello!")
  }
  fork {
    sleep(500.millis)
    throw new RuntimeException("boom!")
  }
}

// doesn't print "Hello", instead throws "boom!"
```

## User, daemon and unsupervised forks

Forks created using `fork` behave as daemon threads. That is, their failure ends the scope, but the scope will also end 
once the body and all user forks succeed, regardless if the (daemon) fork is still running.

Alternatively, a user fork can be created using `forkUser`. Such a fork is required to complete successfully, in order
for the scope to end successfully. Hence, when the body of the scope completes, the scope will wait until all user
forks have completed as well.

Finally, entirely unsupervised forks can be started using `forkPlain`.

## Unsupervised scopes

An unsupervised scope can be created using `unsupervised`. Within such scopes, only `forkPlain` and `forkCancellable` 
forks can be started.

Once the code block passed to `unsupervised` completes, the scope ends, that is, all running forks are cancelled. Still, 
the `unsupervised` method returns (the scope completes) only once all forks have completed.

Fork failures aren't handled in any special way, and can be inspected using the `Fork.join()` method.

For helper method, the capability that needs to be passed is `OxPlain`, a subtype of `Ox` that only allows starting
unsupervised forks.

## Cancelling forks

By default, forks are not cancellable by the user. Instead, all outstanding forks are cancelled (interrupted) when the
enclosing scope ends.

If needed, a cancellable fork can be created using `forkCancellable`. However, such an operation is more expensive, as
it involves creating a nested scope and two virtual threads, instead of one.

The `CancellableFork` trait exposes the `.cancel` method, which interrupts the fork and awaits its completion.
Alternatively, `.cancelNow` returns immediately. In any case, the enclosing scope will only complete once all forks have
completed.
