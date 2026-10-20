package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.telemetry.tracing.OxTracing

export ox.flow.tracing.{flatMapSpanned, mapSpanned, span}

/** Returns the current span, see [[OxTracing.currentSpanUnsafe]]. */
def currentSpanUnsafe(oxTracing: OxTracing) = oxTracing.currentSpanUnsafe()

/** Opens a new span, which has to be closed by the caller, see [[OxTracing.spanUnsafe]]. */
def spanUnsafe(oxTracing: OxTracing)(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty
): AutoCloseableSpan = oxTracing.spanUnsafe(spanName, spanKind, attributes, links)
