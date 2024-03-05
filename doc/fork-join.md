# Fork & join threads

It's safest to use higher-level methods, such as `par` or `race`, however this isn't always sufficient. For
these cases, threads can be started using the structured concurrency APIs described below.

Forks (new threads) can only be started within a **concurrency scope**. Such a scope is defined using the `supervised`,
`supervisedError` or `scoped` methods.

The lifetime of the forks is defined by the structure of the code, and corresponds to the enclosing `supervised` or
`scoped` block. Once the code block passed to the scope completes, any forks that are still running are interrupted.
The whole block will complete only once all forks have completed (successfully, or with an exception).

Hence, it is guaranteed that all forks started within `supervised` or `scoped` will finish successfully, with an
exception, or due to an interrupt.

For example, the code below is equivalent to `par`:

```scala mdoc:compile-only
import ox.{fork, supervised}

supervised {
  val f1 = fork {
    Thread.sleep(2000)
    1
  }

  val f2 = fork {
    Thread.sleep(1000)
    2
  }

  (f1.join(), f2.join())
}
```

It is a compile-time error to use `fork`/`forkUser` outside of a `supervised` or `scoped` block. Helper methods might
require to be run within a scope by requiring the `Ox` capability:

```scala mdoc:compile-only
import ox.{fork, Fork, Ox, supervised}

def forkComputation(p: Int)(using Ox): Fork[Int] = fork {
  Thread.sleep(p * 1000)
  p + 1
}

supervised {
  val f1 = forkComputation(2)
  val f2 = forkComputation(4)
  (f1.join(), f2.join())
}
```

Scopes can be arbitrarily nested.

## Supervision

The default scope, created with `supervised`, watches over the forks that are started within. Any forks started with
`fork` and `forkUser` are by default supervised.

This means that the scope will end only when either:

* all (user, supervised) forks, including the body passed to `supervised`, succeed
* or any (supervised) fork, including the body passed to `supervised`, fails

Hence an exception in any of the forks will cause the whole scope to end. Ending the scope means that all running forks
are cancelled (using interruption). Once all forks complete, the exception is propagated further, that is re-thrown by
the `supervised` method invocation:

```scala mdoc:compile-only
import ox.{fork, forkUser, Ox, supervised}

supervised {
  forkUser {
    Thread.sleep(1000)
    println("Hello!")
  }
  fork {
    Thread.sleep(500)
    throw new RuntimeException("boom!")
  }
}

// doesn't print "Hello", instead throws "boom!"
```

## User, daemon and unsupervised forks

In supervised scopes, forks created using `fork` behave as daemon threads. That is, their failure ends the scope, but
the scope will also end once the body and all user forks succeed, regardless if the (daemon) fork is still running.

Alternatively, a user fork can be created using `forkUser`. Such a fork is required to complete successfully, in order
for the scope to end successfully. Hence, when the body of the scope completes, the scope will wait until all user
forks have completed as well.

Finally, entirely unsupervised forks can be started using `forkUnsupervised`.

## Unsupervised scopes

An unsupervised scope can be created using `scoped`. Any forks started within are unsupervised. This is considered an
advanced feature, and should be used with caution.

Such a scope ends, once the code block passed to `scoped` completes. Then, all running forks are cancelled. Still, the
scope completes (that is, the `scoped` block returns) only once all forks have completed.

Fork failures aren't handled in any special way, and can be inspected using the `Fork.join()` method.

## Cancelling forks

By default, forks are not cancellable by the user. Instead, all outstanding forks are cancelled (interrupted) when the
enclosing scope ends.

If needed, a cancellable fork can be created using `forkCancellable`. However, such an operation is more expensive, as
it involves creating a nested scope and two virtual threads, instead of one.

The `CancellableFork` trait exposes the `.cancel` method, which interrupts the fork and awaits its completion.
Alternatively, `.cancelNow` returns immediately. In any case, the enclosing scope will only complete once all forks have
completed.
