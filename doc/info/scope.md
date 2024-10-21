# Project scope

The areas that we'd like to cover with Ox are:

* concurrency: developer-friendly structured concurrency, high-level concurrency operators, safe low-level primitives, 
  communication between concurrently running computations
* error management: retries, timeouts, a safe approach to error propagation, safe resource management
* scheduling & timers
* resiliency: circuit breakers, bulkheads, rate limiters, backpressure

All of the above should allow for observability of the orchestrated business logic. We aim to enable writing simple, 
expression-oriented code in functional style. We'd like to keep the syntax overhead to a minimum, preserving 
developer-friendly stack traces, and without compromising performance.

Some of the above are already addressed in the API, some are coming up in the future. We'd love your help in shaping the
project!

## Inspiration & building blocks

* [Project Loom](https://openjdk.org/projects/loom/) (virtual threads)
* structured concurrency Java APIs ([JEP 428](https://openjdk.org/jeps/428))
* scoped values ([JEP 429](https://openjdk.org/jeps/429))
* fast, scalable [Go](https://golang.org)-like channels using [jox](https://github.com/softwaremill/jox)
* the [Scala 3](https://www.scala-lang.org) programming language
