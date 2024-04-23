# 5. Application errors

Date: 2024-03-05

## Context

In some cases, it's useful to treat some return values as errors, which should cause the enclosing scope to end.

## Decision

For computation combinators, which include `par`, `race` and `supervised`, we decided to introduce the concept of
application errors. These are values of a shape defined by an `ErrorMode`, which are specially treated by Ox - if
such a value represents an error, the enclosing scope ends.

Some design limitations include:

* we want normal scopes to remain unchanged
* methods requiring a concurrency scope (that is, `using Ox`) should be callable from the new scope
* all forks that might report application errors, must be constrained to return the same type of application errors
* computation combinators, such as `par`, should have a single implmentation both when using application errors and
  exceptions only

Taking this into account, we separate the `Ox` capability, which allows starting forks, and `OxError`, which 
additionally allows reporting application errors. An inheritance hierarchy, `OxError <: Ox` ensures that we can call
methods requiring the `Ox` capability if `OxError` is available, but not the other way round.

Finally, introducing a special `forkError` method allows us to require that it is run within a `supervisedError` scope
and that it must return a value of the correct shape.
