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

Spans are created using an OpenTelemetry `Tracer`, passed as the first argument:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.Tracer
import ox.telemetry.tracing.*

def example(tracer: Tracer): Unit =
  val opened = spanUnsafe(tracer)("my-span")
  opened.close()
```

## Propagating the context

The current span is kept in the OpenTelemetry `Context`, which by default isn't propagated to new virtual threads, e.g.
forks created in [`supervised`](../structured-concurrency/fork-join.md) scopes. Ox tracing therefore **requires** the
`PropagatingVirtualThreadFactory` from [otel-context](otel-context.md) to be used, which describes how to set it up;
without it, spans created in forks won't be children of the span active when the fork was created.

## Spans around flows

[Flows](../streaming/flows.md) can open a span for each element. The span is a child of the current span, and if the code
within it throws, the exception is recorded on the span, and its status is set to `ERROR`.

| Operator                             | The span of an element covers                                                        |
|--------------------------------------|--------------------------------------------------------------------------------------|
| `spanEach(tracer)("name")`           | running the element through all the following stages, up to the first async boundary |
| `spanStages(tracer)("name")(stages)` | running the element through `stages`, isolated from the following stages by a buffer |

### spanEach

`spanEach` covers the stages placed after it, up to the first asynchronous boundary, e.g. `buffer`. As there is
no buffer, the synchronous stages are called from within the span, so their duration is counted:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.Tracer
import ox.flow.Flow
import ox.telemetry.tracing.*

def downstream(tracer: Tracer, flow: Flow[Int]): List[Int] =
  flow
    .spanEach(tracer)("element")
    .map(_ + 1) // covered by the span
    .buffer()   // the span ends here
    .map(_ * 2) // not covered
    .runToList()
```

The span covers also the stages placed after a `flatMap` that the span was created in, as it passes the elements on from
within the span:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.Tracer
import ox.flow.Flow
import ox.telemetry.tracing.*

def inFlatMap(tracer: Tracer, flow: Flow[Int]): List[Int] =
  flow
    .flatMap(x => Flow.fromValues(x, x * 10).spanEach(tracer)("element").map(_ + 1)) // covered by the span
    .map(_ * 2) // also covered
    .runToList()
```

### spanStages

`spanStages` runs each element through the stages added by the given function. The spans can be nested, and the stages
placed after `spanStages` are not part of the span:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.Tracer
import ox.flow.Flow
import ox.telemetry.tracing.*

def stages(tracer: Tracer, flow: Flow[Int]): List[Int] =
  flow
    .spanStages(tracer)("outer")(
      _.map(_ + 1)
        .spanStages(tracer)("inner")(_.filter(_ % 2 == 0)) // a child of "outer"
    )
    .map(_ * 2) // not covered by any span
    .runToList()
```

The stages and the span run in a fork, and the results are passed on through a buffer, of the size given by the
`BufferCapacity` in scope. The following stages are not counted as long as the stages emit at most `BufferCapacity + 1`
elements for one input element; beyond that, the span also includes the time of waiting for free space in the buffer.

### Optional parameters

All the operators accept optional parameters, which reflect the OpenTelemetry span builder: `spanKind`, `attributes` and
`links`.

They also accept a `beforeClose` callback. It's called right before the span is closed, and
receives the span, and the exception thrown by the code covered by the span as `defectOpt`, if any. It can be used to modify the span right before its closed.

```scala mdoc:compile-only
import io.opentelemetry.api.trace.Tracer
import ox.flow.Flow
import ox.telemetry.tracing.*

def withCallback(tracer: Tracer, flow: Flow[Int]): List[Int] =
  flow
    .spanStages(tracer)(
      "process",
      beforeClose = s =>
        s.defectOpt.foreach(e => s.span.setAttribute("error.type", e.getClass.getSimpleName))
        s.span.addEvent("done")
    )(_.map(_ + 1))
    .runToList()
```

## Spans around code

`span` evaluates the given code within a span, which is ended once the code completes. If it throws, the exception is
recorded on the span. Interruptions and `boundary.break` are not failures, so they end the span normally.

`spanError` additionally takes an [`ErrorMode`](../basics/error-handling.md), so that an application error, e.g. a
`Left`, is recorded on the span as well: its status is set to `ERROR`, and an `exception` event is added. The error is not
thrown, but returned as is. To record an error short-circuited with `.ok()`, put `spanError` around the `either` block,
which turns the break into a returned `Left`:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.Tracer
import ox.{EitherMode, either}
import ox.either.ok
import ox.telemetry.tracing.*

def parse(s: String): Either[String, Int] = s.toIntOption.toRight(s"not a number: $s")

def returned(tracer: Tracer): Either[String, Int] =
  println("working").span(tracer)("log")
  parse("abc").spanError(tracer, EitherMode[String]())("parse") // recorded, and returned as a Left

def withOk(tracer: Tracer): Either[String, Int] =
  either { parse("abc").ok() + 1 }.spanError(tracer, EitherMode[String]())("parse") // the break is caught by `either`
```

Both accept the optional parameters and the `beforeClose` callback described above. In `spanError`, its argument also has
the application error as `errorOpt`; without an error mode, all the failures are exceptions, so it's always empty.

## Manual spans

`spanUnsafe` opens a span, which has to be closed by the caller. Closing the returned `AutoCloseableSpan` ends the span
and restores the previously current span. It's unsafe, as forgetting to close it leaves the span open and the current
span unrestored; use it with [`useCloseableInScope`](../utils/resources.md), so that closing is guaranteed.
`currentSpanUnsafe` returns the current span, e.g. to add attributes:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.Tracer
import ox.*
import ox.telemetry.tracing.*

def manual(tracer: Tracer): Unit =
  resourceScope {
    val opened = useCloseableInScope(spanUnsafe(tracer)("manual-span"))
    opened.span.setAttribute("key", "value")
    currentSpanUnsafe().addEvent("working"): Unit
  }
```
