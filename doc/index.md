# ox

Safe direct-style concurrency and resiliency for Scala on the JVM. Requires JDK 21 & Scala 3.

To start using ox, add the `core` dependency as described below. Then, follow one of the topics listed in the menu
to get to know ox's API.

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

.. toctree::
   :maxdepth: 2   
   :caption: High-level concurrency
   
   par
   
.. toctree::
   :maxdepth: 2   
   :caption: Core
   
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
   utility
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
