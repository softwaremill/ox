# Error handling in scopes

How errors are handled depends on the type of concurrency scope that is used.

## Supervised scope

The "default" and recommended scope is created using `supervised`. When this scope is used, any fork created using
`fork` or `forkUser` that fails with an exception, will cause the enclosing scope to end:

```scala
import ox.{forkUser, supervised}

supervised {
  forkUser {
    Thread.sleep(100)
    throw new RuntimeException("boom!")  
  }
  forkUser {
    // other forks will be interrupted
  }
}
// will re-throw the "boom!' exception
```

If an unsupervised fork fails (created using `forkUnsupervised` / `forkCancellable`), that exception will be thrown
when invoking `Fork.join`.

## Supervised scope with application errors

Additionally, supervised scopes can be created with an error mode, which allows ending the scope when a fork returns
a value that is an [application error](error-handling.md). This can be done by using `supervisedError` and `forkError`, 
for example:

```scala
import ox.{EitherMode, forkUserError, supervisedError}

supervisedError(EitherMode[Int]) { 
  forkUserError { Left(10) } 
  Right(()) 
}
// returns Left(10)
```

Even though the body of the scope returns success (a `Right`), the scope ends with an application error (a `Left`), 
which is reported by a user fork. Note that if we used a daemon fork, the scope might have ended before the error
was reported.

Only forks created with `forkError` and `forkUserError` can report application errors, and they **must** return a value 
of the shape as described by the error mode (in the example above, all `forkError`, `forkUserError` and the scope body 
must return an `Either[Int, T]` for arbitrary `T`s).

The behavior of `fork` and `forkUser` in `supervisedError` scopes is unchanged, that is, their return values are not
inspected.

## Unsupervised scopes

In an unsupervised scope (created using `scoped`), failures of the forks won't be reported in any way, unless they
are explicitly joined. Hence, if there's no `Fork.join`, the exception might go unnoticed.
