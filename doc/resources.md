# Resources

## Single scoped resource

Ox provides convenience inline methods to allocate, use and (uninterruptibly) release resources with a try-finally 
block: `use` and `useCloseable`. For example:

```scala mdoc:compile-only
import ox.useCloseable

useCloseable(new java.io.PrintWriter("test.txt")) { writer =>
  writer.println("Hello, world!")
}
```

If a concurrency scope is available (e.g. `supervised`), or there are multiple resources to allocate, consider using the
approach described below, to avoid creating an additional syntactical scope.

## Within a concurrency scope

Resources can be allocated within a concurrency scope. They will be released in reverse acquisition order, after all 
forks started within the scope finish (but before the scope completes). E.g.:

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

### Release-only

You can also register resources to be released (without acquisition logic), before the scope completes:

```scala mdoc:compile-only
import ox.{supervised, releaseAfterScope}

case class MyResource(c: Int)

def release(resource: MyResource): Unit =
  println(s"releasing ${resource.c} ...")

supervised {
  val resource1 = MyResource(10)
  releaseAfterScope(release(resource1))
  println(s"Using $resource1 ...")
}
```
