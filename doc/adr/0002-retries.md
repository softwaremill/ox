# 2. Retries

Date: 2023-11-30

## Context

How should the [retries API](../utils/retries.md) be implemented in terms of:
- developer friendliness,
- supported ways of representing the operation under retry,
- possibly infinite retries.

## Decision

We're using a single, unified syntax to retry and operation:
```scala
retry(operation)(policy)
```
so that the developers don't need to wonder which variant to use.

The operation can be a function returning a result directly, or wrapped in a `Try` or `Either`. Therefore, there are three overloaded variants of the `retry` function.

For possibly infinite retries, we're using tail recursion to be stack safe. This comes at a cost of some code duplication in the retry logic, but is still more readable and easier to follow that a `while` loop with `var`s for passing the state.
