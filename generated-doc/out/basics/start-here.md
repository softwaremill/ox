# Start here

## Adding Ox to your project

```scala
// sbt dependency
"com.softwaremill.ox" %% "core" % "0.2.1"

// scala-cli dependency
//> using dep "com.softwaremill.ox::core:0.2.1"
```

## Scope of the Ox project

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

## Community

If you'd have feedback, development ideas or critique, please head to our [community forum](https://softwaremill.community/c/ox/12)!
Alternatively, you can create an issue or submit a pull request on [GitHub](https://github.com/softwaremill/ox).

## Sponsors

Development and maintenance of Ox is sponsored by [SoftwareMill](https://softwaremill.com), a software development and consulting company. 
We help clients scale their business through software. Our areas of expertise include backends, distributed systems, 
machine learning and data analytics.

[![](https://files.softwaremill.com/logo/logo.png "SoftwareMill")](https://softwaremill.com)

## Commercial Support

We offer commercial support for Ox and related technologies, as well as development services. 
[Contact us](https://softwaremill.com/contact/) to learn more about our offer!

## Introductory articles

* [Prototype Loom-based concurrency API for Scala](https://softwaremill.com/prototype-loom-based-concurrency-api-for-scala/)
* [Go-like channels using project Loom and Scala](https://softwaremill.com/go-like-channels-using-project-loom-and-scala/)
* [Two types of futures](https://softwaremill.com/two-types-of-futures/)
* [Supervision, Kafka and Java 21: what’s new in Ox](https://softwaremill.com/supervision-kafka-and-java-21-whats-new-in-ox/)
* [Designing a (yet another) retry API](https://softwaremill.com/designing-a-yet-another-retry-api/)
* [Handling errors in direct style Scala](https://softwaremill.com/handling-errors-in-direct-style-scala/)

## Inspiration & building blocks

* [Project Loom](https://openjdk.org/projects/loom/) (virtual threads)
* structured concurrency Java APIs ([JEP 428](https://openjdk.org/jeps/428))
* scoped values ([JEP 429](https://openjdk.org/jeps/429))
* fast, scalable [Go](https://golang.org)-like channels using [jox](https://github.com/softwaremill/jox)
* the [Scala 3](https://www.scala-lang.org) programming language
