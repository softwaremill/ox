# Resources

## Allocate & release

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

## Release-only

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
