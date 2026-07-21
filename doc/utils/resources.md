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

If a concurrency scope is available (e.g. `supervised`), or there are multiple resources to allocate, consider using a
resource scope (described below), to avoid creating an additional syntactical scope.

Alternatively, you can use `useInterruptible`, where the releasing might be interrupted, and which is equivalent to a 
`try`-`finally` block.

```{warning}
To properly release resources when the entire application is interrupted, make sure to use [`OxApp`](oxapp.md) as the
application's main entry point.
```

## Resource scopes

To manage multiple resources with a shared lifetime - without involving concurrency - use a **resource scope**.
Resources registered within the scope are released, in reverse registration order, once the scope's body completes
(successfully or with an exception). This is ox's analogue of `scala.util.Using.Manager`:

```scala mdoc:compile-only
import ox.{resourceScope, useCloseableInScope}
import java.io.{FileReader, FileWriter}

def process(): Unit = resourceScope {
  val in = useCloseableInScope(new FileReader("in.txt"))
  val out = useCloseableInScope(new FileWriter("out.txt"))
  // both closed when the scope completes, out first
  out.write(in.read())
}
```

Any concurrency scope (e.g. `supervised`) is also a resource scope, so methods can declare exactly the capability
they need: `using ResourceScope` for attaching cleanup, without claiming the ability to fork.

A resource scope can only be started where no concurrency scope is visible — this is verified at compile-time. The
reason: forks started within a lexically visible resource scope could outlive it, using resources after they have
been released (a concurrency scope doesn't have this problem, as it waits for all forks before releasing). Within a
concurrency scope, register resources directly instead. To use a resource scope e.g. in the body of a fork, extract
it to a method which doesn't take a `using Ox` parameter — a good practice
[in itself](../other/best-practices.md#use-using-ox-sparingly):

```scala mdoc:compile-only
import ox.{forkDiscard, resourceScope, supervised, useCloseableInScope}
import java.io.FileReader

def handleRequest(): Unit = resourceScope {
  val in = useCloseableInScope(new FileReader("in.txt"))
  println(s"Processing the request using: ${in.read()}")
}

supervised {
  forkDiscard(handleRequest())
}
```

## Within a concurrency scope

Resources can also be allocated within a concurrency scope. They will be released in reverse acquisition order, after
all forks started within the scope finish (but before the scope completes). E.g.:

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
