# ox

Safe direct-style concurrency and resiliency for Scala on the JVM. Requires JDK 21 & Scala 3.

To start using ox, add the `core` dependency as described below. Then, follow one of the topics listed in the menu
to get to know ox's API.

In addition to this documentation, ScalaDocs can be browsed at [https://javadoc.io](https://www.javadoc.io/doc/com.softwaremill.ox).

## Add to your project

```scala
// sbt dependency
"com.softwaremill.ox" %% "core" % "@VERSION@"

// scala-cli dependency
//> using dep "com.softwaremill.ox::core:@VERSION@"
```

## Scope of the project

The areas that we'd like to cover with ox are:

* concurrency: developer-friendly structured concurrency, high-level concurrency operators, safe low-level primitives, communication between concurrently running computations
* error management: retries, timeouts, a safe approach to error propagation, safe resource management
* scheduling & timers
* resiliency: circuit breakers, bulkheads, rate limiters, backpressure

All of the above should allow for observability of the orchestrated business logic. We aim to enable writing simple, expression-oriented code in functional style. We'd like to keep the syntax overhead to a minimum, preserving developer-friendly stack traces, and without compromising performance.

Some of the above are already addressed in the API, some are coming up in the future. We'd love your help in shaping the project!

## Community

If you'd have feedback, development ideas or critique, please head to our [community forum](https://softwaremill.community/c/ox/12)!

## Sponsors

Development and maintenance of ox is sponsored by [SoftwareMill](https://softwaremill.com), a software development and consulting company. We help clients scale their business through software. Our areas of expertise include backends, distributed systems, machine learning and data analytics. 

[![](https://files.softwaremill.com/logo/logo.png "SoftwareMill")](https://softwaremill.com)

## Other projects

The wider goal of direct-style Scala is enabling teams to deliver working software quickly and with confidence. Our
other projects, including [sttp client](https://sttp.softwaremill.com) and [tapir](https://tapir.softwaremill.com),
also include integrations directly tailored towards direct-style.

## Commercial Support

We offer commercial support for ox and related technologies, as well as development services. [Contact us](https://softwaremill.com/contact/) to learn more about our offer!

## Introductory articles

* [Prototype Loom-based concurrency API for Scala](https://softwaremill.com/prototype-loom-based-concurrency-api-for-scala/)
* [Go-like channels using project Loom and Scala](https://softwaremill.com/go-like-channels-using-project-loom-and-scala/)
* [Two types of futures](https://softwaremill.com/two-types-of-futures/)
* [Supervision, Kafka and Java 21: what’s new in Ox](https://softwaremill.com/supervision-kafka-and-java-21-whats-new-in-ox/)
* [Designing a (yet another) retry API](https://softwaremill.com/designing-a-yet-another-retry-api/)
* [Handling errors in direct-style Scala](https://softwaremill.com/handling-errors-in-direct-style-scala/)

## Inspiration & building blocks

* [Project Loom](https://openjdk.org/projects/loom/) (virtual threads)
* structured concurrency Java APIs ([JEP 428](https://openjdk.org/jeps/428))
* scoped values ([JEP 429](https://openjdk.org/jeps/429))
* fast, scalable [Go](https://golang.org)-like channels using [jox](https://github.com/softwaremill/jox)
* the [Scala 3](https://www.scala-lang.org) programming language

## Table of contents

```eval_rst
.. toctree::
   :maxdepth: 2
   :caption: Core

   par
   race
   collections
   timeout
   error-handling
   fork-join
   error-handling-scopes
   fork-local
   retries
   interruptions
   resources
   control-flow
   extension
   dictionary
   performance

.. toctree::
   :maxdepth: 2
   :caption: Channels

   channels/index
   channels/sinks
   channels/sources
   channels/channel-closed
   channels/transforming-sources
   channels/discharging
   channels/select
   channels/errors
   channels/backpressure
   channels/actors

.. toctree::
   :maxdepth: 2
   :caption: Kafka integration

   kafka
