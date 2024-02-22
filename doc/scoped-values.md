# Scoped values

Scoped value replace usages of `ThreadLocal` when using virtual threads and structural concurrency. They are useful to
propagate auxiliary context, e.g. trace or correlation ids.

Values are bound structurally as well, e.g.:

```scala
import ox.{ForkLocal, fork, supervised}

val v = ForkLocal("a")
supervised {
  println(v.get()) // "a"
  fork {
    v.scopedWhere("x") {
      println(v.get()) // "x"
      fork {
        println(v.get()) // "x"
      }.join()
    }
  }.join()
  println(v.get()) // "a"
}
```

Scoped values propagate across nested scopes.
