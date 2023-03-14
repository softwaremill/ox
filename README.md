# Ox

A prototype of a Scala concurrency API using virtual threads, structured concurrency ([JEP 428](https://openjdk.org/jeps/428)), 
and scoped values ([JEP 429](https://openjdk.org/jeps/429)). 

Requires JDK 20. Applications need the following JVM flags: `--enable-preview --add-modules jdk.incubator.concurrent`.

SBT dependency:

```scala
"com.softwaremill.ox" %% "core" % "0.0.4"
```

See the [Prototype Loom-based concurrency API for Scala](https://softwaremill.com/prototype-loom-based-concurrency-api-for-scala/) introductory article for more details.
