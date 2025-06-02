# Fork locals

`ForkLocal`s replace usages of `ThreadLocal` when using Ox's forks and structural concurrency. They are useful to
propagate auxiliary context, e.g. trace or correlation ids.

A fork local needs to be first created with a default value. Then, its value can be set within a new [scope](fork-join.md).
Usually, a new supervised scope is created, within which the `ForkLocal` is set to the given value - but only within that
scope, as long as it's not completed. Hence, values are bound structurally:

```scala
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

```{note}
Due to the "structured" nature of setting a fork local's value, forks using external (wider) scopes should not be 
created within a block where a fork local is set. An attempt to do so will throw a 
`java.util.concurrent.StructureViolationException`.
```

## Creating helper functions which set fork locals

If you're writing a helper function which sets a value of a fork local within a passed code block, you have to make
sure that the code block doesn't accidentally capture the outer concurrency scope (leading to an exception on the
first `fork`). 

This can be done by capturing the code block as a context function `Ox ?=> T`, so that any nested invocations of `fork`
will use the provided instance, not the outer one. E.g.:

```scala 
def withSpan[T](spanName: String)(f: Ox ?=> T): T =
  val span = spanBuilder.startSpan(spanName)
  currentSpan.supervisedWhere(Some(span)) {
    try f
    finally span.end()
  }
```

## Implementation notes

`ForkLocal`s are based on an immutable map passed via a `ThreadLocal`, when a fork is started or a value set. The
implementation will instead rely on `ScopedValue`s, which are part of [JEP 506](https://openjdk.org/jeps/506), when
both scoped values and structured concurrency will be available as stable features in an LTS Java release.