# Interruptions

When catching exceptions, care must be taken not to catch & fail to propagate an `InterruptedException`. Doing so will
prevent the scope cleanup mechanisms to make appropriate progress, as the scope won't finish until all started threads
complete.

A good solution is to catch only non-fatal exception using `NonFatal`, e.g.:

```scala mdoc:compile-only
import ox.{forever, fork, supervised}

import org.slf4j.LoggerFactory
import scala.util.control.NonFatal

val logger = LoggerFactory.getLogger(this.getClass)
def processSingleItem(): Unit = ()

supervised {
  fork {
    forever {
      try processSingleItem()
      catch case NonFatal(e) => logger.error("Processing error", e)
    }
  }

  // do something else
}
```
