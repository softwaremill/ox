# Project scope

Ox covers the following areas:

* streaming: push-based backpressured streaming designed for direct-style, with a rich set of stream transformations,
  flexible stream source & sink definitions and reactive streams integration
* error management: retries, timeouts, a safe approach to error propagation, safe resource management  
* concurrency: high-level concurrency operators, developer-friendly structured concurrency, safe low-level primitives, 
  communication between concurrently running computations
* scheduling & timers
* resiliency: circuit breakers, bulkheads, rate limiters, backpressure

Ox enables writing simple, expression-oriented code in funcitonal style. The syntax overhead is kept to a minimum, 
preserving developer-friendly stack traces, and without compromising performance.

## Inspiration & building blocks

* [Project Loom](https://openjdk.org/projects/loom/) (virtual threads)
* structured concurrency Java APIs ([JEP 505](https://openjdk.org/jeps/505))
* scoped values ([JEP 506](https://openjdk.org/jeps/506))
* fast, scalable [Go](https://golang.org)-like channels using [jox](https://github.com/softwaremill/jox)
* the [Scala 3](https://www.scala-lang.org) programming language
