# Ox

[![Ideas, suggestions, problems, questions](https://img.shields.io/badge/Discourse-ask%20question-blue)](https://softwaremill.community/c/ox)
[![CI](https://github.com/softwaremill/ox/workflows/CI/badge.svg)](https://github.com/softwaremill/ox/actions?query=workflow%3A%22CI%22)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.softwaremill.ox/core_3/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.softwaremill.ox/core_3)

Safe direct-style concurrency and resiliency for Scala on the JVM. Requires JDK 21 & Scala 3.

[sbt](https://www.scala-sbt.org) dependency:

```scala
"com.softwaremill.ox" %% "core" % "0.0.22"
```

Documentation is available at [https://ox.softwaremill.com](https://ox.softwaremill.com).

ScalaDocs can be browsed at [https://javadoc.io](https://www.javadoc.io/doc/com.softwaremill.ox).

## Contributing

All suggestions welcome :)

To compile and test, run:

```
sbt compile
sbt test
```

See the list of [issues](https://github.com/softwaremill/ox/issues) and pick one! Or report your own.

If you are having doubts on the _why_ or _how_ something works, don't hesitate to ask a question on
[discourse](https://softwaremill.community/c/ox) or via github. This probably means that the documentation, ScalaDocs or
code is unclear and can be improved for the benefit of all.

In order to develop the documentation, you can use the `doc/watch.sh` script, which runs Sphinx using Python.
Use `doc/requirements.txt` to set up your Python environment with `pip`. Moreover, you can use the 
`compileDocumentation` sbt task to verify, that all code snippets compile properly.

## Project sponsor

We offer commercial development services. [Contact us](https://softwaremill.com) to learn more about us!

## Copyright

Copyright (C) 2023-2024 SoftwareMill [https://softwaremill.com](https://softwaremill.com).
