# Dependency (sbt, scala-cli, etc.)

To use ox core in your project, add:

```scala
// sbt dependency
"com.softwaremill.ox" %% "core" % "@VERSION@"

// scala-cli dependency
//> using dep com.softwaremill.ox::core:@VERSION@
```

Ox core depends on the `resources` module below and the Java [jox](https://github.com/softwaremill/jox) project, where channels are implemented.

To use resource management without Ox's concurrency APIs, add the lightweight `resources` module directly:

```scala
// sbt dependency
"com.softwaremill.ox" %% "resources" % "@VERSION@"

// scala-cli dependency
//> using dep com.softwaremill.ox::resources:@VERSION@
```

The `core` module includes `resources` transitively. The standalone `resources` module depends only on the Scala runtime and has no dependency on Jox.

Integration modules have separate dependencies.
