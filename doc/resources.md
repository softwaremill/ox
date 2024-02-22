# Resources

## In-scope

Resources can be allocated within a scope. They will be released in reverse acquisition order, after the scope completes
(that is, after all forks started within finish). E.g.:

```scala mdoc:compile-only
import ox.{supervised, useInScope}

case class MyResource(c: Int)

def acquire(c: Int): MyResource =
  println(s"acquiring $c ...")
  MyResource(c)

def release(resource: MyResource): Unit =
  println(s"releasing ${resource.c} ...")

supervised {
  val resource1 = useInScope(acquire(10))(release)
  val resource2 = useInScope(acquire(20))(release)
  println(s"Using $resource1 ...")
  println(s"Using $resource2 ...")
}
```

## Supervised / scoped

Resources can also be used in a dedicated scope:

```scala mdoc:compile-only
import ox.useSupervised

case class MyResource(c: Int)

def acquire(c: Int): MyResource =
  println(s"acquiring $c ...")
  MyResource(c)

def release(resource: MyResource): Unit =
  println(s"releasing ${resource.c} ...")

useSupervised(acquire(10))(release) { resource =>
  println(s"Using $resource ...")
}
```

If the resource extends `AutoCloseable`, the `release` method doesn't need to be provided.
