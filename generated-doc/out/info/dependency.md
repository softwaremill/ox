# Dependency (sbt, scala-cli, etc.)

To use ox core in your project, add:

```scala
// sbt dependency
"com.softwaremill.ox" %% "core" % "1.0.0"

// scala-cli dependency
//> using dep com.softwaremill.ox::core:1.0.0
```

Ox core depends only on the Java [jox](https://github.com/softwaremill/jox) project, where channels are implemented. There are no other direct or transitive dependencies.

Integration modules have separate dependencies.