# Ox Library Cursor Rules

## General & Foundation

### ox-direct-style (automatically applied)
Ox promotes direct-style programming where effectful computations return values directly without wrapper types like `Future`, `IO`, or `Task`. This leverages Java 21 virtual threads for high-performance concurrency.

### ox-structured-concurrency (automatically applied)
Use structured concurrency with scopes (`supervised`, `supervisedError`, `unsupervised`) to manage thread lifetimes. All manually managed threading and concurrent operations must be contained within scopes that guarantee cleanup and proper resource management.

### ox-virtual-threads (automatically applied)
Leverage virtual threads - don't be afraid of blocking operations, create many forks when needed.

## Error Handling

### ox-error-handling-dual-channel (automatically applied)
Ox uses two distinct channels for errors: exceptions for bugs/unexpected situations, and application errors as values (`Either`, union types).

### ox-error-propagation
Understand error propagation in scopes: exceptions in supervised forks cause the entire scope to fail and interrupt other forks.

### ox-scopes-application-errors
Use the `supervisedError` scope with error modes (like `EitherMode`) for application errors that should propagate without being exceptions.

## Scope Management

### ox-scope-lifetime
Keep concurrency scopes as small as possible. Create short-lived scopes for single requests, messages, or jobs rather than long-running global scopes.

### ox-scope-selection
Choose appropriate scope types: `supervised` for error propagation, `supervisedError` for application error handling, `unsupervised` for manual error management.

### ox-fork-types
Use appropriate fork types: `fork` (daemon), `forkUser` (user, scope waits), `forkError`/`forkUserError` (with error handling), `forkUnsupervised` (manual management).

## High-Level Concurrency

### ox-prefer-high-level-ops
Prefer high-level operations (`par`, `race`, `timeout`) over manual fork/join patterns when possible for better readability and safety.

### ox-parallel-collections
Use Ox's parallel collection operations (`mapPar`, `collectPar`, `filterPar`, `foreachPar`) for concurrent processing of collections.

### ox-race-timeout-patterns
Use `race` for competing computations and `timeout` for operations that may run too long. Both properly handle interruption of losing branches.

## Streaming & Flows

### ox-streaming-using-flows 
Use Flows to implement streaming, asynchronous data transformation pipelines. Flows integrate with I/O, provide a various ways to integrate with data sources and sinks, as well as declarative intermediate transformations. 

### ox-flow-laziness
Remember that flows are lazy - they only execute when run with `.run*` methods. Use appropriate run methods for your use case.

### ox-flow-concurrency-control
Use flow concurrency operators (`mapPar`, `buffer`, `merge`) to introduce asynchronous boundaries only when needed for performance.

### ox-channel-integration
Use channels for integrating with callback-based APIs. Channels provide completable, queue-like communication with Go-like `select` features, without requiring structured context. 

## Resource Management

### ox-resource-scopes
Use `useCloseableInScope` and similar methods to ensure proper resource cleanup when scopes end, regardless of success or failure.

### ox-oxapp-pattern
Extend `OxApp` for applications that need clean shutdown handling for SIGINT/SIGTERM signals with proper fork cleanup.

## Resilience Patterns

### ox-retry-configuration
Configure retries with appropriate schedules, result policies, and error conditions. Consider using adaptive retries for systemic failure scenarios.

### ox-circuit-breaker-usage
Use circuit breakers for protecting against cascading failures. Configure appropriate failure thresholds and recovery strategies.

### ox-rate-limiting
Apply rate limiting to prevent system overload. Choose appropriate algorithms (fixed window, sliding window, token bucket) based on requirements.

## Performance & Best Practices

### ox-avoid-fork-return
Avoid returning `Fork` objects from methods to prevent accidental concurrency. Model concurrency at the caller level instead.

### ox-using-ox-sparingly
Use `using Ox` capability sparingly as it grants thread creation power. Avoid passing it through multiple method layers.

### ox-actor-isolation
Use actors for stateful components that need isolation and controlled access, especially when integrating with external systems.

### ox-flow-text-processing
Use built-in text transformation operators (`encodeUtf8`, `linesUtf8`, `decodeStringUtf8`) for efficient text processing in flows.

## Integration & Interoperability

### ox-reactive-streams
Use flow-to-publisher conversion for reactive streams integration. Remember that publishers need active scopes to function.

### ox-callback-integration
Integrate callback-based APIs using channels and `Flow.usingEmit` for safe bridging between structured and unstructured concurrency.

### ox-scheduling-patterns
Use scheduling utilities (`repeat`, `scheduled`) for periodic tasks and time-based operations rather than manual sleep loops. 