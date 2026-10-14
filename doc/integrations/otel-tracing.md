# OpenTelemetry tracing

Dependency:

```scala
"com.softwaremill.ox" %% "ox-telemetry" % "@VERSION@"
```

The module depends on [otel-context](otel-context.md), so it doesn't need to be added separately. An OpenTelemetry SDK
and exporter have to be configured by the application, as usual.

Ox provides helpers for creating [OpenTelemetry](https://opentelemetry.io) spans around code that uses Ox's concurrency
operators, including [Flows](../streaming/flows.md). An opened span becomes a child of the current span, and becomes the
current span itself, until it's closed.

Spans are created using an `OxTracing` instance, wrapping an OpenTelemetry `Tracer`:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.Tracer
import ox.telemetry.tracing.OxTracing

def tracing(tracer: Tracer): OxTracing = OxTracing.native(tracer)
```

## Propagating the context

The current span is kept in the OpenTelemetry `Context`, which by default isn't propagated to new virtual threads, e.g.
forks created in [`supervised`](../structured-concurrency/fork-join.md) scopes. `OxTracing.native` relies on the
`PropagatingVirtualThreadFactory` from [otel-context](otel-context.md) to do that, and prints a warning to stderr if
it's not set up. The factory can be set for the whole app when using [`OxApp`](../utils/oxapp.md), or manually through
`setOxThreadFactory`, before the thread factory is first used (that is, before any scope is started, or `OxTracing.native`
is used):

```scala mdoc:compile-only
import ox.*
import ox.otel.context.PropagatingVirtualThreadFactory

object MyApp extends OxApp:
  override def settings: OxApp.Settings = OxApp.Settings.Default.copy(
    threadFactory = Some(PropagatingVirtualThreadFactory())
  )

  def run(args: Vector[String])(using Ox): ExitCode = ExitCode.Success
```

If you want to take care of context propagation yourself, use `OxTracing.custom`.

## Spans around code and flows

`byNameSpan` evaluates a code block within a span. The span is ended once the block completes, and if the block throws,
the exception is recorded on the span, and its status is set to `ERROR` (this includes an interruption). `Flow.span`
does the same for a [`Flow`](../streaming/flows.md): the span is opened when the flow is run, and ended when the flow
completes, fails, or is interrupted. Downstream stages are run within the span as well, and running the flow multiple
times opens a new span each time.

In the example below, the `process-numbers` span is created in a fork, and thanks to the context being propagated, it's
a child of the `request` span:

```scala mdoc:compile-only
import ox.*
import ox.flow.Flow
import ox.telemetry.tracing.*

def example(tracing: OxTracing): Unit =
  val result = supervised {
    fork(Flow.fromValues(1, 2, 3).span(tracing)("process-numbers").runToList()).join()
  }.byNameSpan(tracing)("request")
  println(result)
```

Both accept optional `spanKind`, `attributes` and `links`, as well as a `beforeClose` callback, called right before the
span is closed, e.g. to set attributes or add events. The callback receives the span and the exception thrown by the
spanned code, if any, and has to return the span. If it throws, the exception is added as suppressed to the one thrown
by the spanned code; the span is closed in either case:

```scala mdoc:compile-only
import ox.flow.Flow
import ox.telemetry.tracing.*

def withAttributes(tracing: OxTracing): List[Int] =
  Flow
    .fromValues(1, 2, 3)
    .span(tracing)("process-numbers", beforeClose = (span, error) => span.setAttribute("failed", error.isDefined))
    .runToList()
```

## Manual spans

`spanUnsafe` opens a span, which has to be closed by the caller. Closing the returned `AutoCloseableSpan` ends the span
and restores the previously current span. It's unsafe, as forgetting to close it leaves the span open and the current
span unrestored; use it with [`useCloseableInScope`](../utils/resources.md), so that closing is guaranteed.
`currentSpanUnsafe` returns the current span, e.g. to add attributes:

```scala mdoc:compile-only
import ox.*
import ox.telemetry.tracing.*

def manual(tracing: OxTracing): Unit =
  resourceScope {
    val opened = useCloseableInScope(spanUnsafe(tracing)("manual-span"))
    opened.span.setAttribute("key", "value")
    currentSpanUnsafe(tracing).addEvent("working"): Unit
  }
```
