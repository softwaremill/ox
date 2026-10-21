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

`spanStages` runs each element of a [`Flow`](../streaming/flows.md) through the stages added by the given function,
within a span opened for that element. The span is opened when the element enters the stages, and ended when the last
stage finishes processing everything derived from it. Elements are processed one after another, so each one gets its own
span, and the flow returned by the function is run for each element separately: stateful stages, e.g. `grouped`, don't
carry their state across elements.

If the stages fail, the exception is recorded on the span of the element, and its status is set to `ERROR`. An
interruption and control-flow exits (`boundary.break`) end the span normally, as they aren't failures. The function is
called within the span, so spans opened by it, and by the stages, are the span's children, and if it throws, the
exception is recorded as well.

The stages and the span run in a fork, and the results are passed to the stages placed after `spanStages` through a
buffer, of the size given by the `BufferCapacity` in scope. Because of that boundary, the stages placed after it are not
counted, spans created there are not the span's children, and their failures are not recorded on it. This holds as long
as the stages emit at most `BufferCapacity + 1` elements for one input element, as they are processed without waiting
for downstream. Beyond that, the span also includes the time of waiting for the following stages to free up space in the
buffer. The buffers of the stages placed after it add up, so the more of them, the later it happens. To include more
stages in the span, put them inside the function.

In the example below, the `process-numbers` spans are created in a fork, and thanks to the context being propagated,
they are children of the `request` span:

```scala mdoc:compile-only
import ox.*
import ox.flow.Flow
import ox.telemetry.tracing.*

def example(tracing: OxTracing): Unit =
  val result = resourceScope {
    useCloseableInScope(spanUnsafe(tracing)("request")): Unit
    supervised {
      fork(Flow.fromValues(1, 2, 3).spanStages(tracing)("process-numbers")(_.map(_ + 1)).runToList()).join()
    }
  }
  println(result)
```

Optional parameters are `spanKind`, `attributes` and `links`, as well as a `beforeClose` callback, called right before
the span is closed, e.g. to set attributes or add events. The callback receives the span and the exception thrown by the
stages, if any, and has to return the span. If it throws, the exception is added as suppressed to the one thrown by the
stages; the span is closed in either case:

```scala mdoc:compile-only
import ox.flow.Flow
import ox.telemetry.tracing.*

def withAttributes(tracing: OxTracing): List[Int] =
  Flow
    .fromValues(1, 2, 3)
    .spanStages(tracing)("process-numbers", beforeClose = s => s.span.setAttribute("failed", s.errorOpt.isDefined))(
      _.map(_ + 1).filter(_ % 2 == 0)
    )
    .runToList()
```

`mapSpanned` is `map`, where each call of the function is run within a span. Unlike `spanStages`, it doesn't use a fork
and a buffer, so the span covers only the call of the function:

```scala mdoc:compile-only
import ox.flow.Flow
import ox.telemetry.tracing.*

def perElement(tracing: OxTracing, flow: Flow[Int]): Unit =
  flow.mapSpanned(tracing)("increment")(_ + 1).runDrain()
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
