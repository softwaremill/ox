# Ox Library - Cursor Rules List

## ox-direct-style-overview (automatically applied)
Overview of Ox library for direct-style Scala 3 programming covering streaming, concurrency and resiliency. Ox enables safe concurrent programming with blocking syntax, flexible streaming data pipelines, and comprehensive error handling. Uses JDK 21+ virtual threads for performance without reactive complexity, structured concurrency scopes for thread safety, and dual error channels (exceptions for bugs, Either values for application logic).

## ox-high-level-concurrency-ops
Prefer high-level operations like `par()`, `race()`, and `timeout()` for concurrent programming instead of manual fork management. These operations handle error propagation, interruption, and resource cleanup automatically. Use `.mapPar`, `.filterPar` and `.collectPar` on large collections for added performance.

## ox-dual-error-handling
Use exceptions for bugs/unexpected situations and use `Either` values for application logic errors. Use Rust-like `either` blocks with `.ok()` to unwrap `Either` values with automatic short-circuiting. Combine different error types using union types (`Either[Int | Long, String]`) and avoid nested `either` blocks in the same scope.

## ox-streaming-flows-over-channels
Use Flows for functional-style streaming transformations with methods like `map`, `mapPar`, `filter`, `groupBy` and many others. Flows are lazy, composable, and manage concurrency declaratively and efficiently. Flows only start processing data when run.

## ox-flow-concurrency-control
Control Flow concurrency using `mapPar()`, `mapParUnordered()` with explicit parallelism limits, and `buffer()` for asynchronous boundaries. Use `merge()` for combining multiple flows. Use the various methods to construct Flows such as `.fromEmit` and `.fromChannel` to integrate with third-party sources. Leverage `runToChannel()` to integrate with third-party sinks.

## ox-low-level-structured-concurrency-scopes
Use `supervised` scopes for low-level, manual concurrency to manage thread lifetime safely. Scopes ensure all forked threads complete before the scope ends, preventing thread leaks. Always prefer small, short-lived scopes over global ones, and avoid passing `using Ox` capability through multiple method layers.

## ox-fork-join-patterns
Create concurrent computations using `fork` within concurrency scopes and `join()` to collect results. Use `forkUser` for tasks which should block scope completion. Avoid returning `Fork` instances from methods to prevent accidental concurrency - let callers manage parallelism explicitly.

## ox-resource-management-scopes
Use `useCloseableInScope()` and `releaseAfterScope()` for automatic resource cleanup when scopes end. Resources are properly released even on interruption or errors. Integrate with existing Java AutoCloseable resources and define custom cleanup logic for non-standard resource types.

## ox-oxapp-application-structure
Extend `OxApp` when writing application entry points. The `run` method receives root `Ox` capability and command-line arguments, must return `ExitCode`. Use `OxApp.Simple` for basic cases or `OxApp.WithEitherErrors[E]` for direct-style error handling in main application logic.

## ox-retry-resilience-patterns
Use `retry()` with `Schedule` configurations for resilient operations: `Schedule.exponentialBackoff()`, `Schedule.fixedInterval()` with jitter, max retries, and backoff limits. Combine with `RateLimiter` for rate limiting and `CircuitBreaker` for failure threshold management in distributed systems. Use adaptive retries to avoid overloading systems due to systemic failures.

## ox-scheduling-repeat-patterns
Use `repeat()` with scheduling configurations for periodic tasks: `Schedule.fixedRate()`, `Schedule.fixedInterval()`, and `Schedule.cron()` (with cron extension). Combine with `scheduled()` for delayed execution and integrate scheduling patterns within supervised scopes for proper cleanup.

## ox-low-level-channel-coordination-patterns
Use low-level `Channel`s for Go-like inter-thread communication with `send()`, `receive()`, and `select()` operations. Create buffered channels for throughput, rendezvous channels for synchronization. Use `select` with multiple clauses for non-blocking coordination and integrate callback-based APIs through channel bridging.

## ox-utility-combinators
Leverage ox's utility functions like `pipe()` for method chaining, `tap()` for side effects, `timeout()` for time limits, `debug()` for printing expression values, `.discard` for discarding values and `sleep` for blocking a thread. These maintain direct-style while providing powerful composition patterns.