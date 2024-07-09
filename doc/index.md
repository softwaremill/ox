# Ox

Safe direct-style concurrency and resiliency for Scala on the JVM. Requires JDK 21 & Scala 3.

To start using Ox, add the `core` dependency as described below. Then, follow one of the topics listed in the menu
to get to know Ox's API.

In addition to this documentation, ScalaDocs can be browsed at [https://javadoc.io](https://www.javadoc.io/doc/com.softwaremill.ox).

```{eval-rst}
.. include:: basics/start-here.md
   :parser: markdown
```

## Table of contents

```{eval-rst}
.. toctree::
   :maxdepth: 2
   :caption: Basics
   
   basics/start-here
   basics/direct-style
   basics/quick-example
   basics/error-handling

.. toctree::
   :maxdepth: 2   
   :caption: High-level concurrency
   
   high-level-concurrency/par
   high-level-concurrency/race
   high-level-concurrency/collections
   high-level-concurrency/timeout

.. toctree::
   :maxdepth: 2   
   :caption: Structured concurrency
   
   structured-concurrency/index
   structured-concurrency/fork-join
   structured-concurrency/error-handling-scopes
   structured-concurrency/fork-local
   structured-concurrency/interruptions
   
.. toctree::
   :maxdepth: 2   
   :caption: Resiliency, I/O & utilities
   
   oxapp
   io
   retries
   resources
   control-flow
   utility

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
   channels/io

.. toctree::
   :maxdepth: 2
   :caption: Kafka integration

   kafka

.. toctree::
   :maxdepth: 2
   :caption: Other topics
   
   dictionary
   best-practices
   performance
   compare-gears
   compare-funeff
