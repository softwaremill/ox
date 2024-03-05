# General approach to error handling

The primary error signalling mechanism in ox are exceptions. They are appropriately handled by computation combinators,
such as [`par`](par.md), [`race`](race.md), as well as by [scopes](fork-join.md) and [channels](channels/index.md).

The general rule for computation combinators is that using them should throw exactly the same exceptions, as if the 
provided code was executed directly. That is, no additional exceptions might be thrown, and no exceptions are swallowed. 
The only difference is that some exceptions might be added as suppressed (e.g. interrupted exceptions).

Some examples of exception handling in ox include:

* short-circuiting in `par` and `race` when one of the computations fails
* retrying computations in `retry` when they fail
* ending a `supervised` concurrency scope when a supervised fork fails

## Application errors

Some of the functionalities provided by ox also support application-level errors. Such errors are represented as values,
e.g. the left side of an `Either[MyError, MyResult]`. They are not thrown, but returned from the computations which
are orchestrated by ox.

Ox must be made aware of how such application errors are represented. This is done through an `ErrorMode`. Provided
implementations include `EitherMode[E]` (where left sides of `Either`s are used to represent errors), and 
`UnionMode[E]`, where a union type of `E` and a successful value is used. Arbitrary user-provided implementations
are possible as well.

Error modes can be used in [`supervisedError`](error-handling-scopes.md) scopes, as well as in variants of the `par`
and `race` methods.

```eval_rst
.. note::

  Using application errors allows specifying the possible errors in the type signatures of the methods, and is hence 
  more type-safe. If used consistently, exceptions might be avoided altogether, except for signalling bugs in the code.
  However, representing errors as values might incur a syntax overhead, and might be less convenient in some cases.
  Moreover, all I/O libraries typically throw exceptions - to use them with errors-as-values, one would need to provide
  a wrapper which would convert such exceptions to values. Hence, while application errors provide a lot of benefits,
  they are not a universal solution to error handling.
```
