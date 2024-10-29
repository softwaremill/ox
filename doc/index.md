# Ox

Safe direct-style concurrency and resiliency for Scala on the JVM. Requires JDK 21 & Scala 3.

To start using Ox, add the `com.softwaremill.ox::core:@VERSION@` [dependency](info/dependency.md) to your project. 
Then, take a look at the tour of Ox, or follow one of the topics listed in the menu to get to know Ox's API!

In addition to this documentation, ScalaDocs can be browsed at [https://javadoc.io](https://www.javadoc.io/doc/com.softwaremill.ox).

```{include} tour.md
```

## Table of contents

```{eval-rst}

.. toctree::
   :maxdepth: 2
   :caption: Project info

   info/community-support
   info/dependency
   info/scope

.. toctree::
   :maxdepth: 2
   :caption: Basics
   
   tour
   basics/direct-style
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
   :caption: Streaming

   streaming/index
   streaming/channels
   streaming/transforming-channels
   streaming/selecting-from-channels
   streaming/flows
   streaming/io
   streaming/errors
   streaming/backpressure
   
.. toctree::
   :maxdepth: 2   
   :caption: Resiliency & utilities
   
   utils/oxapp
   utils/retries
   utils/rate-limiter
   utils/custom-rate-limiter
   utils/repeat
   utils/scheduled
   utils/resources
   utils/control-flow
   utils/actors
   utils/utility

.. toctree::
   :maxdepth: 2
   :caption: Integrations

   integrations/kafka
   integrations/mdc-logback

.. toctree::
   :maxdepth: 2
   :caption: Other topics
   
   other/links
   other/dictionary
   other/best-practices
   other/performance
   other/compare-gears
   other/compare-funeff
