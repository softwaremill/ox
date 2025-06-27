# Ox Library - Cursor Rules List

## ox-direct-style-overview (automatically applied)
Overview of Ox library for direct-style Scala programming using virtual threads and structured concurrency. Ox enables safe concurrent programming with blocking-like syntax, Go-like channels, and comprehensive error handling. Uses JDK 21+ virtual threads for performance without reactive complexity, structured concurrency scopes for thread safety, and dual error channels (exceptions for bugs, Either values for application logic).

## ox-structured-concurrency-scopes
Use `supervised` and `unsupervised` scopes to manage concurrent thread lifetime safely. Scopes ensure all forked threads complete before the scope ends, preventing thread leaks. Always prefer small, short-lived scopes over global ones, and avoid passing `using Ox` capability through multiple method layers.

## ox-fork-join-patterns
Create concurrent computations using `fork` within scopes and `join()` to collect results. Use `forkUser` for user-level tasks and `forkDaemon` for background work. Avoid returning `Fork` instances from methods to prevent accidental concurrency - let callers manage parallelism explicitly.

## ox-high-level-concurrency-ops
Prefer high-level operations like `par()`, `race()`, and `timeout()` for concurrent programming instead of manual fork management. These operations handle error propagation, interruption, and resource cleanup automatically. Use `raceSuccess()` to get the first successful result while canceling other branches.

## ox-streaming-flows-over-channels
Use Flows for functional-style streaming transformations with methods like `map`, `mapPar`, `filter`, `groupBy`. Flows are lazy, composable, and manage concurrency efficiently. Only use imperative Channels when you need fine-grained control over blocking send/receive operations or complex coordination patterns.

## ox-dual-error-handling
Handle exceptions for bugs/unexpected situations and use `Either` values for application logic errors. Use `either` blocks with `.ok()` to unwrap `Either` values with automatic short-circuiting. Combine different error types using union types (`Either[Int | Long, String]`) and avoid nested `either` blocks in the same scope.

## ox-resource-management-scopes
Use `useCloseableInScope()` and `releaseAfterScope()` for automatic resource cleanup when scopes end. Resources are properly released even on interruption or errors. Integrate with existing Java AutoCloseable resources and define custom cleanup logic for non-standard resource types.

## ox-oxapp-application-structure
Extend `OxApp` for applications requiring proper SIGINT/SIGTERM handling and clean shutdown. The `run` method receives root `Ox` capability and command-line arguments, must return `ExitCode`. Use `OxApp.Simple` for basic cases or `OxApp.WithEitherErrors[E]` for direct-style error handling in main application logic.

## ox-retry-resilience-patterns
Use `retry()` with `Schedule` configurations for resilient operations: `Schedule.exponentialBackoff()`, `Schedule.fixedInterval()` with jitter, max retries, and backoff limits. Combine with `RateLimiter` for rate limiting and `CircuitBreaker` for failure threshold management in distributed systems.

## ox-channel-coordination-patterns
Use Channels for Go-like inter-thread communication with `send()`, `receive()`, and `select()` operations. Create buffered channels for throughput, rendezvous channels for synchronization. Use `select` with multiple clauses for non-blocking coordination and integrate callback-based APIs through channel bridging.

## ox-flow-concurrency-control
Control Flow concurrency using `mapPar()`, `mapParUnordered()` with explicit parallelism limits, and `async()` for asynchronous boundaries. Use `buffer()` for backpressure management and `merge()` for combining multiple flows. Leverage `runToChannel()` and `runForeach()` for different consumption patterns.

## ox-scheduling-repeat-patterns
Use `repeat()` with scheduling configurations for periodic tasks: `Schedule.fixedRate()`, `Schedule.fixedInterval()`, and `Schedule.cron()` (with cron extension). Combine with `scheduled()` for delayed execution and integrate scheduling patterns within supervised scopes for proper cleanup.
