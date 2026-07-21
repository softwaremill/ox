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

Resources can be attached to a **resource scope**: they are then released when the scope completes (successfully or
with an exception), in reverse registration order. Releasing is uninterruptible. Resources are registered using
`useInScope` (with acquire & release logic) or `useCloseableInScope` (for `AutoCloseable`s). A resource scope can be
obtained in two ways:

* `resourceScope` starts a dedicated, resource-only scope - without involving concurrency. This is ox's analogue of
  `scala.util.Using.Manager`:

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

* every concurrency scope (e.g. `supervised`) is also a resource scope; resources registered within one are released
  after all forks started within the scope finish (but before the scope completes):

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

Methods which only register resources can declare exactly the capability they need - `using ResourceScope` - without
claiming the ability to fork.

A resource scope can only be started where no concurrency scope is visible - this is verified at compile-time. The
reason: forks started within a lexically visible resource scope could outlive it, using resources after they have
been released (a concurrency scope doesn't have this problem, as it waits for all forks before releasing). Within a
concurrency scope, register resources directly instead. To use a resource scope e.g. in the body of a fork, extract
it to a method which doesn't take a `using Ox` parameter - a good practice
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
