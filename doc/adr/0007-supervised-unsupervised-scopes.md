# 7. Supervised & unsupervised scopes

Date: 2024-04-17

## Context

Originally, ox had only `scoped` which created non-supervised scopes, that is errors were only discovered via explicit
joining. This was later changed by introducing `supervised` and `unsupervised` scopes, where the former would end
immediately when any fork failed, and the latter would not. However, `supervised` scopes have an overhead: they create
an additional fork, in which the scope's main body is run. Is it possible to avoid this extra fork?

## Decision

In short: no.

An alternate design would be to store the thread, that created the scope as part of the supervisor, and when any 
exception occurs, interrupt that thread so that it would discover the exception. However, this would be prone to
interruption-races with external interruptions of that main thread. Even if we included an additional flag, specifying
if the interruption happened because the scope ends, it would still be possible for an external interrupt to go
unnoticed (if it happened at the same time, as the internal one). Even though unlikely, such a design would be fragile, 
hence we are keeping the current implementation.

## Consequences

To make our design more type-safe, we split the `Ox` capability into `OxPlain` (allowing only unsupervised forks), and
`Ox`. The `plain` nomeclature was chosen to indicate that the scope is unsupervised, however `forkPlain` is much shorter 
than e.g. `forkUnsupervised`. Hence introducing a new name seems justified.
