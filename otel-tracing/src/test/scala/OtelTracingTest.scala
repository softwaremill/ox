package ox.otel.tracing

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.{BatchSpanProcessor, SimpleSpanProcessor}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import ox.*
import ox.either.ok
import ox.flow.Flow
import ox.otel.context.PropagatingVirtualThreadFactory

import java.util.UUID
import java.util.concurrent.Semaphore
import scala.jdk.CollectionConverters.*
import scala.util.boundary.Label

class OtelTracingTest extends AnyWordSpec with Matchers with BeforeAndAfterAll:
  override def beforeAll(): Unit =
    setOxThreadFactory(new PropagatingVirtualThreadFactory)

  val exporter = InMemorySpanExporter.create()

  // To inspect the spans visually, run an OTLP backend and set the endpoint, e.g.:
  //   docker run --rm -p 16686:16686 -p 4317:4317 jaegertracing/all-in-one
  //   OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 sbt otel/test
  // and open http://localhost:16686 (service `otel-tracing-test`).
  val tracerProvider =
    val builder = SdkTracerProvider
      .builder()
      .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "otel-tracing-test")))
      .addSpanProcessor(SimpleSpanProcessor.create(exporter))
    sys.env
      .get("OTEL_EXPORTER_OTLP_ENDPOINT")
      .foreach: endpoint =>
        builder.addSpanProcessor(BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build()).build())
    builder.build()
  end tracerProvider

  override def afterAll(): Unit = tracerProvider.shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS): Unit

  val openTelemetry =
    OpenTelemetrySdk
      .builder()
      .setTracerProvider(tracerProvider)
      .build()

  val tracer = openTelemetry.getTracer("test")

  enum Level:
    case Level0
    case Level1
    case Level1_First
    case Level1_First_Child
    case Level1_Stage

  import Level.*

  class Fixture:
    val prefix = s"[${UUID.randomUUID()}]"
    val flow = Flow.fromValues(1, 2, 3)

    def testSpans = exporter.getFinishedSpanItems.asScala.filter(_.getName.startsWith(prefix))

    def spansOf(level: Level) = testSpans.filter(_.getName.endsWith(level.toString))

    def assertNesting(): Unit =
      val level0 = spansOf(Level0)
      val level1First = spansOf(Level1_First)

      val level1 = spansOf(Level1) ++ level1First
      val level1FirstChildren = spansOf(Level1_First_Child)

      assert(level0.nonEmpty, "expected Level0 spans to exist for nesting check")
      assert(level1.nonEmpty, "expected Level1 spans to exist for nesting check")
      level1.foreach { s =>
        assert(level0.exists(_.getSpanId == s.getParentSpanId), s"Level1 span ${s.getName} has no Level0 parent")
      }

      if level1FirstChildren.nonEmpty then
        assert(level1First.nonEmpty, "expected Level1_First spans to exist for nesting check")
        level1FirstChildren.foreach { s =>
          assert(level1First.exists(_.getSpanId == s.getParentSpanId), s"Level1_First_Child span ${s.getName} has no Level1_First parent")
        }
    end assertNesting
  end Fixture

  "span" when {
    "a stage fails" should {
      "record exception and set ERROR status when the flow fails" in new Fixture:
        val parent = spanUnsafe(tracer)(s"$prefix-$Level0")

        val _ = intercept[Exception]:
          flow
            .spanEach(tracer)(
              s"$prefix-$Level1",
              beforeClose = s => s.exceptionOpt.foreach(e => s.span.addEvent(e.toString))
            )
            .map { case 2 => throw new Exception("boom"); case x => x }
            .runToList()

        parent.close()

        assert(spansOf(Level1).size == 2)
        val failed = spansOf(Level1).find(_.getStatus.getStatusCode == StatusCode.ERROR).get
        assert(failed.getStatus.getDescription == "boom")
        val otelEvent = failed.getEvents.asScala.find(_.getName == "exception").get
        assert(otelEvent.getAttributes.get(AttributeKey.stringKey("exception.message")) == "boom")
        // custom beforeClose event: e.toString contains the exception message
        assert(failed.getEvents.asScala.exists(e => e.getName != "exception" && e.getName.contains("boom")))
        assertNesting()

      "record the error returned by a span in supervisedError, ending the span, and continue with the following elements" in new Fixture:
        val em = EitherMode[String]

        val result = flow
          .flatMap: el =>
            supervisedError(em):
              spanError(tracer, em)(s"$prefix-$Level1")(
                either:
                  val buffered = Flow.fromValues(el).buffer().runToList()
                  if el == 1 then em.pureError("ops").ok() else buffered
              )
            .fold(_ => Flow.empty[Int], Flow.fromIterable)
          .runToList()

        assert(result == List(2, 3))
        val spans = spansOf(Level1).sortBy(_.getStartEpochNanos)
        assert(spans.size == 3)
        assert(spans.head.getStatus.getStatusCode == StatusCode.ERROR)
        assert(spans.head.getStatus.getDescription == "ops")
        assert(spans.tail.forall(_.getStatus.getStatusCode == StatusCode.UNSET))
    }

    "spanEach" should {
      "not open a span until the flow is run" in new Fixture:
        flow.spanEach(tracer)(s"$prefix-$Level0"): Unit
        assert(testSpans.isEmpty)

      "open a span per element, around the stages placed after it" in new Fixture:
        val parent = spanUnsafe(tracer)(s"$prefix-$Level0")

        flow
          .spanEach(tracer)(s"$prefix-$Level1_First")
          .map(x => span(tracer)(s"$prefix-$Level1_First_Child")(x))
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 3)
        assert(spansOf(Level1_First_Child).size == 3)
        assertNesting()

      "not cover the stages placed before it" in new Fixture:
        val parent = spanUnsafe(tracer)(s"$prefix-$Level0")

        flow
          .map(x => span(tracer)(s"$prefix-$Level1")(x))
          .spanEach(tracer)(s"$prefix-$Level1_First")
          .runToList(): Unit

        parent.close()

        val level0Id = spansOf(Level0).head.getSpanId
        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1_First).size == 3)
        // Level1 spans were opened before spanEach, so they must be children of Level0, not Level1_First
        assert(spansOf(Level1).forall(_.getParentSpanId == level0Id))
        assertNesting()

      "count the stages placed after it, also outside of the flatMap it is used in" in new Fixture:
        val parent = spanUnsafe(tracer)(s"$prefix-$Level0")

        Flow
          .fromValues(1)
          .flatMap(x => Flow.fromValues(x).spanEach(tracer)(s"$prefix-$Level1"))
          .map(y => span(tracer)(s"$prefix-$Level1_First")(y)) // outside the flatMap, still covered by the spanEach span
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1).size == 1)
        assert(spansOf(Level1_First).size == 1)
        // Level1_First span was opened while spanEach span was active, so it must be its child
        val spanEachSpanId = spansOf(Level1).head.getSpanId
        assert(spansOf(Level1_First).head.getParentSpanId == spanEachSpanId)

      "end the span at a buffer placed after it" in new Fixture:
        val parent = spanUnsafe(tracer)(s"$prefix-$Level0")

        flow
          .spanEach(tracer)(s"$prefix-$Level1")
          .buffer()
          .map(x => span(tracer)(s"$prefix-$Level1_Stage")(x)) // after buffer: not a child of spanEach
          .runToList(): Unit

        parent.close()

        val level0Id = spansOf(Level0).head.getSpanId
        assert(spansOf(Level1).size == 3)
        // spans after the buffer are children of Level0, not of the spanEach spans
        assert(spansOf(Level1_Stage).forall(_.getParentSpanId == level0Id))

      "have all element spans as children of the enclosing span when run concurrently" in new Fixture:
        val parent = spanUnsafe(tracer)(s"$prefix-$Level0")

        supervised {
          (1 to 5).foreach(_ => fork(flow.spanEach(tracer)(s"$prefix-$Level1").runToList()))
        }

        parent.close()

        assert(spansOf(Level1).size == 15)
        assertNesting()

    }

    "spanError" should {
      val em = EitherMode[String]()

      "return the result and not mark the span as failed when t succeeds" in new Fixture:
        val result = spanError(tracer, em)(s"$prefix-$Level0")(Right(1))

        assert(result == Right(1))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.UNSET)
        assert(span.getEvents.isEmpty)

      "record an application error on the span and return it as is" in new Fixture:
        val result = spanError(tracer, em)(s"$prefix-$Level0")(Left("bad"): Either[String, Int])

        assert(result == Left("bad"))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "bad")
        val event = span.getEvents.asScala.find(_.getName == "exception").get
        assert(event.getAttributes.get(AttributeKey.stringKey("exception.message")) == "bad")
        assert(event.getAttributes.get(AttributeKey.stringKey("exception.type")) == "java.lang.String")

      "record an application error which is an exception as an exception" in new Fixture:
        val result = spanError(tracer, EitherMode[Exception]())(
          s"$prefix-$Level0"
        )(Left(new Exception("boom")): Either[Exception, Int])

        assert(result.isLeft)
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "boom")
        val event = span.getEvents.asScala.find(_.getName == "exception").get
        assert(event.getAttributes.get(AttributeKey.stringKey("exception.type")) == "java.lang.Exception")

      "pass the application error to beforeClose" in new Fixture:
        var seen: SpanWithError[String] | Null = null

        spanError(tracer, em)(
          s"$prefix-$Level0",
          beforeClose = s => seen = s
        )(Left("bad"): Either[String, Int]): Unit

        assert(seen.nn.errorOpt == Some("bad"))
        assert(seen.nn.exceptionOpt.isEmpty)

      "record a thrown exception and pass it to beforeClose" in new Fixture:
        var seen: SpanWithError[String] | Null = null

        val thrown = intercept[Exception]:
          spanError(tracer, em)(
            s"$prefix-$Level0",
            beforeClose = s => seen = s
          )((throw new Exception("boom")): Either[String, Int]): Unit

        assert(thrown.getMessage == "boom")
        assert(seen.nn.exceptionOpt.map(_.getMessage) == Some("boom"))
        assert(seen.nn.errorOpt.isEmpty)
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "boom")

      "record an application error short-circuited with ok, when spanError is placed around the either block" in new Fixture:
        val result = spanError(tracer, em)(s"$prefix-$Level0")(either {
          val x: Int = (Left("bad"): Either[String, Int]).ok()
          x
        })

        assert(result == Left("bad"))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "bad")

      "return the application error, and close the span, when beforeClose throws" in new Fixture:
        val result =
          spanError(tracer, em)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("cb"))(Left("bad"): Either[String, Int])

        assert(result == Left("bad"))
        assert(spansOf(Level0).size == 1)

      "throw from beforeClose, and close the span, when t succeeds" in new Fixture:
        val thrown = intercept[Exception]:
          spanError(tracer, em)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("cb"))(Right(1): Either[String, Int])

        assert(thrown.getMessage == "cb")
        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)
    }

    "cancelled" should {
      "end span when the enclosing fork is cancelled" in new Fixture:
        val semaphore = Semaphore(0)

        supervised {
          val forkWithSpan = forkCancellable {
            flow
              .spanEach(tracer)(s"$prefix-$Level1")
              .tap { case 1 => semaphore.release(); case _ => sleep(100.seconds) }
              .runToList()
          }
          semaphore.acquire()
          forkWithSpan.cancelNow()
        }

        // the first element's span was started; interruption is not a failure
        assert(spansOf(Level1).nonEmpty)
        assert(spansOf(Level1).forall(_.getStatus.getStatusCode == StatusCode.UNSET))

      "not mark the span as failed when the code exits with a boundary break" in new Fixture:
        val result = scala.util.boundary:
          span(tracer)(s"$prefix-$Level0")(scala.util.boundary.break(1))
          2

        assert(result == 1)
        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)
        assert(spansOf(Level0).head.getEvents.isEmpty)

      "not pass a boundary break, a control throwable or an interruption to beforeClose" in new Fixture:
        var seen: List[Option[Throwable]] = Nil

        def record(s: SpanWithError[Nothing]) =
          seen = s.exceptionOpt :: seen

        val _ = scala.util.boundary:
          span(tracer)(s"$prefix-$Level0", beforeClose = record)(scala.util.boundary.break(1))
        val _ = intercept[scala.util.control.ControlThrowable]:
          span(tracer)(s"$prefix-$Level0", beforeClose = record)(throw new scala.util.control.ControlThrowable {})
        val _ = intercept[InterruptedException]:
          span(tracer)(s"$prefix-$Level0", beforeClose = record)(throw new InterruptedException())

        assert(seen == List(None, None, None))
        assert(spansOf(Level0).size == 3)
        assert(spansOf(Level0).forall(_.getStatus.getStatusCode == StatusCode.UNSET))

      "treat an interruption during I/O as not a failure" in new Fixture:
        var seen: Option[Throwable] = Some(new Exception("unset"))
        val _ = intercept[java.nio.channels.ClosedByInterruptException]:
          span(tracer)(
            s"$prefix-$Level0",
            beforeClose = s => seen = s.exceptionOpt
          )(throw new java.nio.channels.ClosedByInterruptException())

        assert(seen.isEmpty)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "record the closing of a channel by another thread as a failure" in new Fixture:
        val _ = intercept[java.nio.channels.AsynchronousCloseException]:
          span(tracer)(s"$prefix-$Level0")(throw new java.nio.channels.AsynchronousCloseException())

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "treat an ExecutionException wrapping an interruption as not a failure" in new Fixture:
        var seen: Option[Throwable] = Some(new Exception("unset"))
        val _ = intercept[java.util.concurrent.ExecutionException]:
          span(tracer)(
            s"$prefix-$Level0",
            beforeClose = s => seen = s.exceptionOpt
          )(throw new java.util.concurrent.ExecutionException(new InterruptedException()))

        assert(seen.isEmpty)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "treat an ExecutionException wrapping a failure as a failure" in new Fixture:
        val _ = intercept[java.util.concurrent.ExecutionException]:
          span(tracer)(s"$prefix-$Level0")(throw new java.util.concurrent.ExecutionException(new Exception("boom")))

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "record fatal errors as failures" in new Fixture:
        val _ = intercept[LinkageError]:
          span(tracer)(s"$prefix-$Level0")(throw new LinkageError("broken"))

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "add the exception of beforeClose as suppressed to an interruption, which is still thrown" in new Fixture:
        val interruption = new InterruptedException()

        val thrown = intercept[InterruptedException]:
          span(tracer)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("from beforeClose"))(throw interruption)

        assert(thrown eq interruption)
        assert(thrown.getSuppressed.exists(_.getMessage == "from beforeClose"))
        assert(spansOf(Level0).size == 1)

      "add the exception of beforeClose as suppressed to a normal exception, which is still thrown" in new Fixture:
        val cause = new Exception("cause")

        val thrown = intercept[Exception]:
          span(tracer)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("from beforeClose"))(throw cause)

        assert(thrown eq cause)
        assert(thrown.getSuppressed.exists(_.getMessage == "from beforeClose"))
        assert(spansOf(Level0).size == 1)

      "restore the previous span as current after span returns" in new Fixture:
        val outer = spanUnsafe(tracer)(s"$prefix-$Level0")
        span(tracer)(s"$prefix-$Level1")(())
        assert(currentSpanUnsafe() eq outer.span)
        outer.close()

      "restore the previous span as current after span throws" in new Fixture:
        val outer = spanUnsafe(tracer)(s"$prefix-$Level0")
        val _ = intercept[Exception]:
          span(tracer)(s"$prefix-$Level1")(throw new Exception("boom"))
        assert(currentSpanUnsafe() eq outer.span)
        outer.close()
    }

    "optional parameters" should {
      "pass spanKind, attributes and links to the span builder" in new Fixture:
        import io.opentelemetry.api.trace.SpanKind
        import io.opentelemetry.api.common.{AttributeKey, Attributes}

        val attr = Attributes.of(AttributeKey.stringKey("k"), "v")
        span(tracer)(
          s"$prefix-$Level0",
          spanKind = SpanKind.PRODUCER,
          attributes = attr
        )(())

        val s = spansOf(Level0).head
        assert(s.getKind == SpanKind.PRODUCER)
        assert(s.getAttributes.get(AttributeKey.stringKey("k")) == "v")

      "pass spanKind and attributes to spanEach" in new Fixture:
        import io.opentelemetry.api.trace.SpanKind
        import io.opentelemetry.api.common.{AttributeKey, Attributes}

        val parent = spanUnsafe(tracer)(s"$prefix-$Level0")
        val attr = Attributes.of(AttributeKey.stringKey("k"), "v")

        flow
          .spanEach(tracer)(s"$prefix-$Level1", spanKind = SpanKind.CONSUMER, attributes = attr)
          .runToList(): Unit

        parent.close()

        val spans = spansOf(Level1)
        assert(spans.size == 3)
        assert(spans.forall(_.getKind == SpanKind.CONSUMER))
        assert(spans.forall(_.getAttributes.get(AttributeKey.stringKey("k")) == "v"))
    }
  }
end OtelTracingTest
