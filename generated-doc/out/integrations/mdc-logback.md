# Inheritable MDC using Logback

Dependency:

```scala
"com.softwaremill.ox" %% "mdc-logback" % "0.5.9"
```

Ox provides support for setting inheritable MDC (mapped diagnostic context) values, when using the [Logback](https://logback.qos.ch)
logging library. Normally, value set using `MDC.put` aren't inherited across (virtual) threads, which includes forks
created in concurrency contexts.

Inheritable values are especially useful e.g. when setting a correlation id in an HTTP request interceptor, or at any
entrypoint to the application. Such correlation id values can be then added automatically to each log message, provided
the appropriate log encoder pattern is used.

To enable using inheritable MDC values, the application's code should call `InheritableMDC.init` as soon as possible.
The best place would be the application's entrypoint (the `main` method).

Once this is done, inheritable MDC values can be set in a scoped & structured manner using `InheritableMDC.supervisedWhere`
and variants.

As inheritable MDC values use a [`ForkLocal`](../structured-concurrency/fork-local.md) under the hood, their usage 
restrictions apply: outer concurrency scopes should not be used to create forks within the scopes. Only newly created
scopes, or the provided scope can be used to create forks. That's why `supervisedError`, `unsupervisedError` and
`supervisedErrorWhere` methods are provided.

"Normal" MDC usage is not affected. That is, values set using `MDC.put` are not inherited, and are only available in 
the thread where they are set.

For example:

```scala
import org.slf4j.MDC

import ox.fork
import ox.logback.InheritableMDC

InheritableMDC.supervisedWhere("a" -> "1", "b" -> "2") {
  MDC.put("c", "3") // not inherited

  fork {
    MDC.get("a") // "1"
    MDC.get("b") // "2"
    MDC.get("c") // null
  }.join()

  MDC.get("a") // "1"
  MDC.get("b") // "2"
  MDC.get("c") // "3"
}
```
