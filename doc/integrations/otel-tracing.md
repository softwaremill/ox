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

def tracing(tracer: Tracer): OxTracing = OxTracing(tracer)
```

## Propagating the context

The current span is kept in the OpenTelemetry `Context`, which by default isn't propagated to new virtual threads, e.g.
forks created in [`supervised`](../structured-concurrency/fork-join.md) scopes. `OxTracing` therefore **requires** the
`PropagatingVirtualThreadFactory` from [otel-context](otel-context.md) to be used; without it, spans created in forks
won't be children of the span active when the fork was created. The factory can be set for the whole app when using
[`OxApp`](../utils/oxapp.md), or manually through `setOxThreadFactory`, before the thread factory is first used (that
is, before any scope is started):

```scala mdoc:compile-only
import ox.*
import ox.otel.context.PropagatingVirtualThreadFactory

object MyApp extends OxApp:
  override def settings: OxApp.Settings = OxApp.Settings.Default.copy(
    threadFactory = Some(PropagatingVirtualThreadFactory())
  )

  def run(args: Vector[String])(using Ox): ExitCode = ExitCode.Success
```

## Spans around flows

`Flow.span` evaluates a [`Flow`](../streaming/flows.md) within a span: the span is opened when the flow is run, and ended
when the flow completes, fails, or is interrupted. If the flow fails, the exception is recorded on the span, and its
status is set to `ERROR`. An interruption and control-flow exits (`boundary.break`) end the span normally, as they
aren't failures. Running the flow multiple times opens a new span each time.

The flow and the span run in a fork, and the elements are passed to the stages placed after `span` through a buffer, of
the size given by the `BufferCapacity` in scope. Because of that boundary, the span doesn't depend on which stages
follow it: it covers the flow and everything upstream of it, until the last element is passed to the buffer. The stages
placed after it are not counted, spans created there are not its children, and their failures are not recorded on it.
To include more stages in the span, put them inside the spanned flow.

In the example below, the `process-numbers` span is created in a fork, and thanks to the context being propagated, it's
a child of the `request` span:

```scala mdoc:compile-only
import ox.*
import ox.flow.Flow
import ox.telemetry.tracing.*

def example(tracing: OxTracing): Unit =
  val result = resourceScope {
    useCloseableInScope(spanUnsafe(tracing)("request")): Unit
    supervised {
      fork(Flow.fromValues(1, 2, 3).span(tracing)("process-numbers").runToList()).join()
    }
  }
  println(result)
```

Optional parameters are `spanKind`, `attributes` and `links`, as well as a `beforeClose` callback, called right before
the span is closed, e.g. to set attributes or add events. The callback receives the span and the exception thrown by the
spanned flow, if any, and has to return the span. If it throws, the exception is added as suppressed to the one thrown
by the spanned flow; the span is closed in either case:

```scala mdoc:compile-only
import ox.flow.Flow
import ox.telemetry.tracing.*

def withAttributes(tracing: OxTracing): List[Int] =
  Flow
    .fromValues(1, 2, 3)
    .span(tracing)("process-numbers", beforeClose = s => s.span.setAttribute("failed", s.errorOpt.isDefined))
    .runToList()
```

## Spans per element

The following stages open a span per element, each covering only what is passed to it, so the span doesn't depend on the
stages placed after it:

 - `mapSpanned` is `map`, where each call of the function is run within a span,
 - `flatMapSpanned` is `flatMap`, where each flow returned by the function is run as with `span`.

The flow returned by the function is run for each element separately, so stateful stages, e.g. `grouped`, don't carry
their state across elements.

```scala mdoc:compile-only
import ox.flow.Flow
import ox.telemetry.tracing.*

def perElement(tracing: OxTracing, flow: Flow[Int]): Unit =
  // one function
  flow.mapSpanned(tracing)("increment")(_ + 1).runDrain()

  // several stages, sequentially
  flow
    .flatMapSpanned(tracing)("element")(x => Flow.fromValues(x).map(_ + 1).filter(_ % 2 == 0))
    .runDrain()
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
