# Fork locals

`ForkLocal`s replace usages of `ThreadLocal` when using ox's forks and structural concurrency. They are useful to
propagate auxiliary context, e.g. trace or correlation ids.

Implementation note: `ForkLocal`s are based on `ScopedValue`s, which are part of [JEP 429](https://openjdk.org/jeps/429).

A fork local needs to be first created with a default value. Then, its value can be set within a new [scope](fork-join.md).
Usually, a new supervised scope is created, within which the `ForkLocal` is set to the given value - but only within that
scope, as long as it's not completed. Hence, values are bound structurally:

```scala mdoc:compile-only
import ox.{ForkLocal, fork, supervised}

val v = ForkLocal("a")
supervised {
  println(v.get()) // "a"
  fork {
    v.supervisedWhere("x") {
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
