package ox.telemetry

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.{StatusCode, Tracer}
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
import ox.channels.BufferCapacity
import ox.either.ok
import ox.flow.Flow
import ox.otel.context.PropagatingVirtualThreadFactory
import ox.telemetry.tracing.*

import java.util.UUID
import java.util.concurrent.Semaphore
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.util.boundary
import scala.util.boundary.Label

class OxTelemetryTest extends AnyWordSpec with Matchers with BeforeAndAfterAll:
  override def beforeAll(): Unit =
    setOxThreadFactory(new PropagatingVirtualThreadFactory)

  val exporter = InMemorySpanExporter.create()

  // To inspect the spans visually, run an OTLP backend and set the endpoint, e.g.:
  //   docker run --rm -p 16686:16686 -p 4317:4317 jaegertracing/all-in-one
  //   OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 sbt otel/test
  // and open http://localhost:16686 (service `ox-telemetry-test`).
  val tracerProvider =
    val builder = SdkTracerProvider
      .builder()
      .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "ox-telemetry-test")))
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

  val myTracing: Tracer = tracer

  enum Level:
    case Level0
    case Level1
    case Level1_First
    case Level1_First_Child
    case Level1_Stage
    case Level1_Downstream

  import Level.*

  class Fixture:
    val prefix = s"[${UUID.randomUUID()}]"
    val flow = Flow.fromValues(1, 2, 3)

    def testSpans = exporter.getFinishedSpanItems.asScala.filter(_.getName.startsWith(prefix))

    def spansOf(level: Level) = testSpans.filter(_.getName.endsWith(level.toString))

    def durMs(span: io.opentelemetry.sdk.trace.data.SpanData): Long = (span.getEndEpochNanos - span.getStartEpochNanos) / 1000000

    def startOffsetMs(from: io.opentelemetry.sdk.trace.data.SpanData, to: io.opentelemetry.sdk.trace.data.SpanData): Long =
      (to.getStartEpochNanos - from.getStartEpochNanos) / 1000000

    // sleeps guarantee the lower bound, the upper one leaves room for scheduling jitter
    def assertAbout(actualMs: Long, expectedMs: Long): Unit =
      assert(actualMs >= expectedMs - 5 && actualMs < expectedMs + 250, s"expected ~$expectedMs ms, was $actualMs ms")

    def assertNesting(): Unit =
      val level0 = spansOf(Level0)
      val level1First = spansOf(Level1_First)

      val level1 = spansOf(Level1) ++ level1First
      val level1FirstChildren = spansOf(Level1_First_Child)

      level1.foreach { s =>
        assert(level0.exists(_.getSpanId == s.getParentSpanId))
      }

      level1FirstChildren.foreach { s =>
        assert(level1First.exists(_.getSpanId == s.getParentSpanId))
      }
    end assertNesting
  end Fixture

  "Flow.span" when {
    "run normally" should {
      "not open a span until the flow is run" in new Fixture:
        Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level0")).buffer(): Unit
        assert(testSpans.isEmpty)
        assertNesting()

      "open a single span for a multi-element flow" in new Fixture:
        Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level0")).buffer().runDrain()

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assertNesting()

      "handle buffer one span per span() call when spans are nested" in new Fixture:
        supervised {

          val spanned1 = Flow
            .usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1"))
            .buffer()
            .map(_ + 100)
            .tap(i => currentSpanUnsafe().addEvent(i.toString))

          Flow.usingEmit[Int](emit => spanned1.runToEmit(emit).span(myTracing)(s"$prefix-$Level0")).buffer().runDrain()

          assert(spansOf(Level0).size == 1)
          assert(spansOf(Level1).size == 1)
          assert(testSpans.size == 2)
          assertNesting()
        }
      "open one span per span() call when spans are nested" in new Fixture:
        supervised {
          val spanned1 =
            (Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer() ++ Flow.fromValues(4, 2))
              .tap(i => currentSpanUnsafe().addEvent(i.toString))

          Flow.usingEmit[Int](emit => spanned1.runToEmit(emit).span(myTracing)(s"$prefix-$Level0")).buffer().runDrain()

          assert(spansOf(Level0).size == 1)
          assert(spansOf(Level1).size == 1)
          assert(testSpans.size == 2)
          assertNesting()
        }

      "open a single span when downstream uses mapPar" in new Fixture:
        Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level0")).buffer().mapPar(3)(_ + 1).runDrain()

        assert(testSpans.size == 1)

        val only = spansOf(Level0).head
        assert(only.getStatus.getStatusCode == StatusCode.UNSET)
        assert(only.getEvents.isEmpty)
        assertNesting()
    }

    "used under parent spans" should {
      "use the current span as parent" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer().runToList(): Unit

        parent.close()

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 1)
        assertNesting()

      "open one child span per element when flatMapped under a parent span" in new Fixture:
        val spanned = Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer()

        Flow
          .usingEmit[Int](emit => (flow.flatMap { _ => spanned }.runToEmit(emit)).span(myTracing)(s"$prefix-$Level0"))
          .buffer()
          .runToList(): Unit

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 3)
        assertNesting()

      "parent spans of concurrently run flows under the enclosing span" in new Fixture:
        val spanned = Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer()

        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        supervised {
          (1 to 10).foreach(_ => fork(spanned.runToList()))
        }

        parent.close()

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 10)
        assertNesting()

      "nest spans across forks and inner supervised scopes" in new Fixture:
        val spanned = Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer()

        span(supervised {
          (1 to 10).map {
            case 1 =>
              forkUser(
                Flow
                  .usingEmit[Int](emit =>
                    (flow
                      .flatMap { el =>
                        val innerFlow = supervised {
                          Flow
                            .usingEmit[Int](emit =>
                              flow
                                .runToEmit(emit)
                                .span(myTracing)(
                                  s"$prefix-$Level1_First_Child",
                                  beforeClose = _.span.addEvent("boomed")
                                )
                            )
                            .buffer()
                        }
                        innerFlow
                      }
                      .runToEmit(emit))
                      .span(myTracing)(s"$prefix-$Level1_First")
                  )
                  .buffer()
                  .runToList()
              )

            case _ => forkUser(spanned.runToList())
          }
        })(myTracing)(s"$prefix-$Level0")

        assert(spansOf(Level1).size == 9)
        assert(spansOf(Level1_First).size == 1)
        assert(spansOf(Level1_First_Child).size == 3)

        assertNesting()
    }

    "a stage fails" should {
      "record exception and set ERROR status when the flow fails" in new Fixture:
        try
          Flow
            .usingEmit[Int](emit =>
              (flow
                .tap { case 2 => throw new Exception("boom"); case _ => () }
                .runToEmit(emit))
                .span(myTracing)(
                  s"$prefix-$Level0",
                  beforeClose = { case SpanWithError(s, thr, _) =>
                    s.addEvent(thr.toString)
                  }
                )
            )
            .buffer()
            .runToList()
        catch case _ => ()
        end try

        assert(testSpans.size == 1)

        val failed = spansOf(Level0).head
        assert(failed.getStatus.getStatusCode == StatusCode.ERROR)
        assert(failed.getStatus.getDescription == "boom")
        assert(failed.getEvents.asScala.exists(_.getName == "exception"))
        assertNesting()

      "keep the flow span as a child of the recovery span when failure is recovered" in new Fixture:
        val spanned = Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer()

        Flow
          .usingEmit[Int](emit =>
            (spanned
              .map { case 2 => throw new Exception("boom"); case x => x }
              .recover(_ => 5)
              .runToEmit(emit))
              .span(myTracing)(s"$prefix-$Level0")
          )
          .buffer()
          .runToList(): Unit

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 1)
        assertNesting()

      "end spans of concurrent flows when one of them fails" in new Fixture:
        val spanned = Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer()

        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        try
          supervised {
            (1 to 10).foreach {
              case 1 => forkUser(spanned.tap(_ => throw new Exception("fds")).runToList())
              case _ => forkUser(spanned.runToList())
            }
          }
        catch case _ => ()
        end try
        parent.close()

        assert(spansOf(Level0).size == 1)
        assertNesting()

      "end inner spans with event when nested flow fails in supervisedError" in new Fixture:
        val em = EitherMode[String]

        val level0 = spanUnsafe(myTracing)(s"$prefix-$Level0")

        val spanned = Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer()

        try
          supervised {
            (1 to 10).map {
              case 1 =>
                forkUser {
                  Flow
                    .usingEmit[Int](emit =>
                      (flow
                        .flatMap { el =>
                          {
                            val inner = supervisedError(em):
                              either:
                                val innerFlow =
                                  em.pure(
                                    Flow
                                      .usingEmit[Int](emit =>
                                        flow
                                          .runToEmit(emit)
                                          .span(myTracing)(
                                            s"$prefix-$Level1_First_Child",
                                            beforeClose = _.span.addEvent(s"boomed at $el")
                                          )
                                      )
                                      .buffer()
                                      .map {
                                        case 1 => em.pureError("ops").ok();
                                        case x => x
                                      }
                                  )
                                innerFlow.ok()
                            inner
                          }.toOption.get
                        }
                        .runToEmit(emit))
                        .span(myTracing)(s"$prefix-$Level1_First")
                    )
                    .buffer()
                    .runToList()
                }
              case _ => forkUser(spanned.runToList())
            }
          }
        catch case _ => ()
        end try

        level0.close()
        assert(spansOf(Level1_First_Child).size == 1) // because flow.flatMap is evaluated sequentially and exception ends whole flow
        assertNesting()

      "end inner spans with event when nested flow fails and failure is replaced by empty flow" in new Fixture:
        val em = EitherMode[String]

        val level0 = spanUnsafe(myTracing)(s"$prefix-$Level0")

        val spanned = Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer()

        try
          supervised {
            (1 to 10).map {
              case 1 =>
                forkUser {
                  Flow
                    .usingEmit[Int](emit =>
                      (flow
                        .flatMap { el =>
                          {
                            val inner = supervisedError(em):
                              either:
                                val innerFlow =
                                  em.pure(
                                    Flow
                                      .usingEmit[Int](emit =>
                                        flow
                                          .runToEmit(emit)
                                          .span(myTracing)(
                                            s"$prefix-$Level1_First_Child",
                                            beforeClose = _.span.addEvent(s"boomed at $el")
                                          )
                                      )
                                      .buffer()
                                      .map {
                                        case 1 => em.pureError("ops").ok();
                                        case x => x
                                      }
                                  )
                                innerFlow.ok()
                            inner
                          }.toOption.getOrElse(Flow.empty)
                        }
                        .runToEmit(emit))
                        .span(myTracing)(s"$prefix-$Level1_First")
                    )
                    .buffer()
                    .runToList()
                }
              case _ => forkUser(spanned.runToList())
            }
          }
        catch case _ => ()
        end try

        level0.close()
        assert(spansOf(Level1_First_Child).size == 1) // because flow.flatMap is evaluated sequentially and exception ends whole flow
        assertNesting()

      "end inner spans with event when nested flow fails inside enclosing supervisedError" in new Fixture:
        val em = EitherMode[String]

        val level0 = spanUnsafe(myTracing)(s"$prefix-$Level0")

        val spanned = Flow.usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level1")).buffer()

        try
          supervisedError(em) {
            either:
              em.pure:
                (1 to 10).map {
                  case 1 =>
                    forkUser {
                      Flow
                        .usingEmit[Int](emit =>
                          (flow
                            .flatMap { el =>
                              {
                                supervisedError(em):
                                  val innerFlow =
                                    em.pure(
                                      Flow
                                        .usingEmit[Int](emit =>
                                          flow
                                            .runToEmit(emit)
                                            .span(myTracing)(
                                              s"$prefix-$Level1_First_Child",
                                              beforeClose = _.span.addEvent(s"boomed at $el")
                                            )
                                        )
                                        .buffer()
                                        .map {
                                          case 1 => em.pureError("ops").ok();
                                          case x => x
                                        }
                                    )
                                  innerFlow
                              }.toOption.get
                            }
                            .runToEmit(emit))
                            .span(myTracing)(s"$prefix-$Level1_First")
                        )
                        .buffer()
                        .runToList()
                    }
                  case _ => forkUser(spanned.runToList())
                }
          }
        catch case _ => ()
        end try

        level0.close()
        assert(spansOf(Level1_First_Child).size == 1) // because flow.flatMap is evaluated sequentially and exception ends whole flow
        assertNesting()
    }

    "used with async stages downstream" should {
      "make a span after buffer a sibling of the whole-run span" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        Flow
          .usingEmit[Int](emit =>
            (flow
              .runToEmit(emit))
              .span(myTracing)(s"$prefix-$Level1")
          )
          .buffer()
          .buffer()
          .map(x => x.span(myTracing)(s"$prefix-$Level1_Stage"))
          .runToList(): Unit

        parent.close()

        val level0Id = spansOf(Level0).head.getSpanId
        assert(spansOf(Level1).size == 1)
        assert(spansOf(Level1_Stage).size == 3)
        assert(spansOf(Level1_Stage).forall(_.getParentSpanId == level0Id))

      // one element, with sleeps far apart: a = first generator, b = second generator, c = stage placed after the whole comprehension
      val a = 200.millis
      val b = 600.millis
      val c = 1200.millis
      val (aMs, bMs, cMs) = (a.toMillis, b.toMillis, c.toMillis)

      "open a span per file with span in map, covering only the download" in new Fixture:
        val filesToDownload = Seq(1, 2)

        def download(file: Int): Int =
          sleep(a)
          file

        def readLines(file: Int): Flow[String] = Flow.fromValues(s"line of $file").tap(_ => sleep(b))

        def streamLinesFlow(file: Int) =
          Flow.usingEmit[String](emit => readLines(file).runToEmit(emit).span(myTracing)(s"$prefix-$Level1_First")).buffer()

        (for
          file <- Flow.fromIterable(filesToDownload).map(file => download(file).span(myTracing)(s"$prefix-$Level1"))
          line <- streamLinesFlow(file).recoverWith(_ => streamLinesFlow(file))
        yield line).tap(_ => sleep(c)).runToList(): Unit

        val downloads = spansOf(Level1).sortBy(_.getStartEpochNanos)
        val lines = spansOf(Level1_First)
        assert(downloads.size == 2 && lines.size == 2)
        // the span ends once `download` returns, so neither the lines nor the following stages are counted
        downloads.foreach(s => assertAbout(durMs(s), aMs))
        lines.foreach(s => assertAbout(durMs(s), bMs))
        // the span is closed before its result is emitted, so the lines spans are not its children
        assert(lines.forall(l => !downloads.exists(_.getSpanId == l.getParentSpanId)))
        // the second file is downloaded only after the first one is fully processed
        assertAbout(startOffsetMs(downloads(0), downloads(1)), aMs + bMs + cMs)

      "count only its own flow in a flat for comprehension with spannedAsync" in new Fixture:
        (for
          x <- Flow.fromValues(1)
          y <- Flow
            .usingEmit[Int](emit => Flow.fromValues(x).tap(_ => sleep(a)).runToEmit(emit).span(myTracing)(s"$prefix-$Level1"))
            .buffer()
          z <- Flow
            .usingEmit[Int](emit => Flow.fromValues(y).tap(_ => sleep(b)).runToEmit(emit).span(myTracing)(s"$prefix-$Level1_First"))
            .buffer()
        yield z)
          .tap(_ => sleep(c))
          .runToList(): Unit

        val y = spansOf(Level1).head
        val z = spansOf(Level1_First).head
        assert(z.getParentSpanId != y.getSpanId)
        assertAbout(durMs(y), aMs)
        assertAbout(durMs(z), bMs)
        assertAbout(startOffsetMs(y, z), aMs)

      "nest the spans and durations of a spanStages inside a spanStages" in new Fixture:

        // flow
        // .spanStages(myTracing)(s"outerSpan1")(
        // _.map(...).filter(...).spanStages(myTracing)(s"inner")(_.map(...).buffer().retry(...).map(...))
        // )
        // .spanStages(myTracing(s"outerSpan2"))(_.map(...))
        // .tap(_ => sleep(c)) //not included in spans
        // .runToList(): Unit

        Flow
          .fromValues(1)
          .spanStages(myTracing)(s"$prefix-$Level1")(
            _.tap(_ => sleep(a)).spanStages(myTracing)(s"$prefix-$Level1_First")(_.tap(_ => sleep(b)))
          )
          .tap(_ => sleep(c))
          .runToList(): Unit

        val outer = spansOf(Level1).head
        val inner = spansOf(Level1_First).head
        assert(inner.getParentSpanId == outer.getSpanId)
        assertAbout(durMs(outer), aMs + bMs)
        assertAbout(durMs(inner), bMs)
        assertAbout(startOffsetMs(outer, inner), aMs)
        assert(inner.getEndEpochNanos <= outer.getEndEpochNanos)

      "make chained spanStages spans siblings, each covering only its own flow" in new Fixture:
        Flow
          .fromValues(1)
          .spanStages(myTracing)(s"$prefix-$Level1")(_.tap(_ => sleep(a)))
          .spanStages(myTracing)(s"$prefix-$Level1_First")(_.tap(_ => sleep(b)))
          .tap(_ => sleep(c))
          .runToList(): Unit

        val first = spansOf(Level1).head
        val second = spansOf(Level1_First).head
        assert(second.getParentSpanId != first.getSpanId)
        assertAbout(durMs(first), aMs)
        assertAbout(durMs(second), bMs)
        assertAbout(startOffsetMs(first, second), aMs)

    }

    "spanEach" should {
      "open a span per element, around the stages placed after it" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .spanEach(myTracing)(s"$prefix-$Level1_First")
          .map(x => x.span(myTracing)(s"$prefix-$Level1_First_Child"))
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 3)
        assert(spansOf(Level1_First_Child).size == 3)
        assertNesting()

      "not cover the stages placed before it" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .map(x => x.span(myTracing)(s"$prefix-$Level1"))
          .spanEach(myTracing)(s"$prefix-$Level1_First")
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1_First).size == 3)
        assertNesting()

      "count the stages placed after it, also outside of the flatMap it is used in" in new Fixture:
        Flow
          .fromValues(1, 2, 3)
          .flatMap(x => Flow.fromValues(x).spanEach(myTracing)(s"$prefix-$Level1").tap(_ => sleep(200.millis)))
          .tap(_ => sleep(200.millis))
          .runToList(): Unit

        assert(spansOf(Level1).size == 3)
        spansOf(Level1).foreach(s => assertAbout(durMs(s), 400))

      "end the span at a buffer placed after it" in new Fixture:
        Flow
          .fromValues(1, 2, 3)
          .spanEach(myTracing)(s"$prefix-$Level1")
          .tap(_ => sleep(200.millis))
          .buffer()
          .tap(_ => sleep(200.millis))
          .runToList(): Unit

        assert(spansOf(Level1).size == 3)
        spansOf(Level1).foreach(s => assertAbout(durMs(s), 200))
    }

    "spanError" should {
      val em = EitherMode[String]()

      "return the result and not mark the span as failed when t succeeds" in new Fixture:
        val result = Right(1).spanError(myTracing, em)(s"$prefix-$Level0")

        assert(result == Right(1))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.UNSET)
        assert(span.getEvents.isEmpty)

      "record an application error on the span and return it as is" in new Fixture:
        val result = (Left("bad"): Either[String, Int]).spanError(myTracing, em)(s"$prefix-$Level0")

        assert(result == Left("bad"))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "bad")
        val event = span.getEvents.asScala.find(_.getName == "exception").get
        assert(event.getAttributes.get(AttributeKey.stringKey("exception.message")) == "bad")
        assert(event.getAttributes.get(AttributeKey.stringKey("exception.type")) == "java.lang.String")

      "record an application error which is an exception as an exception" in new Fixture:
        val result = (Left(new Exception("boom")): Either[Exception, Int]).spanError(myTracing, EitherMode[Exception]())(
          s"$prefix-$Level0"
        )

        assert(result.isLeft)
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "boom")
        val event = span.getEvents.asScala.find(_.getName == "exception").get
        assert(event.getAttributes.get(AttributeKey.stringKey("exception.type")) == "java.lang.Exception")

      "pass the application error to beforeClose" in new Fixture:
        var seen: SpanWithError[String] | Null = null

        (Left("bad"): Either[String, Int]).spanError(myTracing, em)(
          s"$prefix-$Level0",
          beforeClose = s =>
            seen = s
            s.span
        ): Unit

        assert(seen.nn.errorOpt == Some("bad"))
        assert(seen.nn.defectOpt.isEmpty)

      "record a thrown exception and pass it to beforeClose" in new Fixture:
        var seen: SpanWithError[String] | Null = null

        val thrown = intercept[Exception]:
          ((throw new Exception("boom")): Either[String, Int]).spanError(myTracing, em)(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s
              s.span
          ): Unit

        assert(thrown.getMessage == "boom")
        assert(seen.nn.defectOpt.map(_.getMessage) == Some("boom"))
        assert(seen.nn.errorOpt.isEmpty)
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "boom")

      "record an application error carried by a break, and propagate the break" in new Fixture:
        var seen: SpanWithError[String] | Null = null

        val result: Either[String, Int] = either:
          ({
            val x: Int = (Left("bad"): Either[String, Int]).ok()
            Right(x)
          }).spanError(myTracing, em)(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s
              s.span
          ).ok()

        assert(result == Left("bad"))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "bad")
        assert(seen.nn.errorOpt == Some("bad"))
        assert(seen.nn.defectOpt.isEmpty)

      "record an application error carried by a break thrown by beforeClose, and propagate the break, when t succeeds" in new Fixture:
        val result: Either[String, Either[String, Int]] = either:
          (Right(1): Either[String, Int]).spanError(myTracing, em)(
            s"$prefix-$Level0",
            beforeClose = s =>
              (Left("cb"): Either[String, Unit]).ok()
              s.span
          )

        assert(result == Left("cb"))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "cb")

      "return the application error of t when beforeClose breaks with another one" in new Fixture:
        val result: Either[String, Either[String, Int]] = either:
          (Left("bad"): Either[String, Int]).spanError(myTracing, em)(
            s"$prefix-$Level0",
            beforeClose = s =>
              (Left("cb"): Either[String, Unit]).ok()
              s.span
          )

        assert(result == Right(Left("bad")))
        val span = spansOf(Level0).head
        assert(span.getStatus.getDescription == "bad")

      "not mark the span as failed when a break carries a value which is not an error" in new Fixture:
        val result = boundary[Either[String, Int]]:
          (boundary.break(Right(1)): Either[String, Int]).spanError(myTracing, em)(s"$prefix-$Level0")

        assert(result == Right(1))
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "return the application error, and close the span, when beforeClose throws" in new Fixture:
        val result =
          (Left("bad"): Either[String, Int]).spanError(myTracing, em)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("cb"))

        assert(result == Left("bad"))
        assert(spansOf(Level0).size == 1)

      "throw from beforeClose, and close the span, when t succeeds" in new Fixture:
        val thrown = intercept[Exception]:
          (Right(1): Either[String, Int]).spanError(myTracing, em)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("cb"))

        assert(thrown.getMessage == "cb")
        assert(spansOf(Level0).size == 1)
    }

    "spanStages per element" should {
      def durationMs(span: io.opentelemetry.sdk.trace.data.SpanData) = (span.getEndEpochNanos - span.getStartEpochNanos) / 1000000

      "open spans created in the body of the function, before it returns the flow, as children of the span" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .spanStages(myTracing)(s"$prefix-$Level1_First")(_.flatMap(x =>
            (()).span(myTracing)(s"$prefix-$Level1_First_Child")
            Flow.fromValues(x).map(y => y.span(myTracing)(s"$prefix-$Level1_Stage"))
          ))
          .runToList(): Unit

        parent.close()

        val elementIds = spansOf(Level1_First).map(_.getSpanId).toSet
        assert(spansOf(Level1_First).size == 3)
        // created by f itself, which is called within the span
        assert(spansOf(Level1_First_Child).size == 3 && spansOf(Level1_First_Child).forall(c => elementIds.contains(c.getParentSpanId)))
        // created by the returned flow, so within the span
        assert(spansOf(Level1_Stage).size == 3 && spansOf(Level1_Stage).forall(s => elementIds.contains(s.getParentSpanId)))

      "call the function in the fork of the span, as the returned flow is run" in new Fixture:
        val caller = Thread.currentThread()
        val inF = java.util.concurrent.ConcurrentLinkedQueue[Thread]()
        val inFlow = java.util.concurrent.ConcurrentLinkedQueue[Thread]()

        flow
          .spanStages(myTracing)(s"$prefix-$Level1_First")(_.flatMap(x =>
            inF.add(Thread.currentThread()): Unit
            Flow.fromValues(x).tap(_ => inFlow.add(Thread.currentThread()): Unit)
          ))
          .runToList(): Unit

        assert(inF.size == 3 && inF.toArray.forall(_ ne caller))
        assert(inFlow.size == 3 && inFlow.toArray.forall(_ ne caller))

      "record the exception thrown by the function before it returns the flow on the span of the element" in new Fixture:
        val thrown = intercept[Exception]:
          flow
            .spanStages(myTracing)(s"$prefix-$Level1_First")(
              _.flatMap(x => if x == 2 then throw new Exception("boom") else Flow.fromValues(x))
            )
            .runToList(): Unit

        // it reaches the caller through the fork, wrapped
        assert(thrown.getMessage.contains("boom"))
        // the first element is fine, the second one fails in the function, which is called within the span
        val spans = spansOf(Level1_First).sortBy(_.getStartEpochNanos)
        assert(spans.size == 2)
        assert(spans.head.getStatus.getStatusCode == StatusCode.UNSET)
        assert(spans(1).getStatus.getStatusCode == StatusCode.ERROR)
        assert(spans(1).getStatus.getDescription == "boom")
        assert(spans(1).getEvents.asScala.exists(_.getName == "exception"))

      "emit the same elements as flatMap" in new Fixture:
        val f = (x: Int) => Flow.fromValues(x, x * 10)

        assert(flow.spanStages(myTracing)(s"$prefix-$Level1_First")(_.flatMap(f)).runToList() == flow.flatMap(f).runToList())

      "open a span per element, with the spans created in the inner flows as children and the following ones as siblings" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .spanStages(myTracing)(s"$prefix-$Level1_First")(
            _.mapConcat(x => 1 to x).map(y => y.span(myTracing)(s"$prefix-$Level1_First_Child"))
          )
          .map(x => x.span(myTracing)(s"$prefix-$Level1"))
          .runToList(): Unit

        parent.close()

        // elements 1, 2, 3 are flatMapped to 1, 2, 3 outputs
        assert(spansOf(Level1_First).size == 3)
        assert(spansOf(Level1_First_Child).size == 6)
        assert(spansOf(Level1).size == 6)
        assertNesting()

      "emit the elements of the inner flows in order" in new Fixture:
        val result = flow.spanStages(myTracing)(s"$prefix-$Level0")(_.flatMap(x => Flow.fromValues(x, x * 10))).runToList()

        assert(result == List(1, 10, 2, 20, 3, 30))
        assert(spansOf(Level0).size == 3)

      "not count the time of the following stages" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow.spanStages(myTracing)(s"$prefix-$Level1")(identity).tap(_ => sleep(200.millis)).runToList(): Unit

        parent.close()

        // each inner flow is done quickly, while the following stage takes 200ms per element
        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1).forall(durationMs(_) < 150))

      "count the time of the following stages, except for the last element, when the buffer is 0" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        given BufferCapacity = BufferCapacity(0)
        flow.spanStages(myTracing)(s"$prefix-$Level1")(_.mapConcat(x => List(x, x * 10))).tap(_ => sleep(200.millis)).runToList(): Unit

        parent.close()

        // the first element is handed over only once downstream is done with the previous one, the second one is handed over right away
        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1).forall(s => durationMs(s) >= 150 && durationMs(s) < 350))

      "not count the time of the following stages when the inner flow has buffer + 1 elements" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        given BufferCapacity = BufferCapacity(1)
        flow.spanStages(myTracing)(s"$prefix-$Level1")(_.mapConcat(x => List(x, x * 10))).tap(_ => sleep(200.millis)).runToList(): Unit

        parent.close()

        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1).forall(durationMs(_) < 150))

      "count the time of the following stages when the inner flow has more than buffer + 1 elements" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        given BufferCapacity = BufferCapacity(1)
        flow
          .spanStages(myTracing)(s"$prefix-$Level1")(_.mapConcat(x => List(x, x * 10, x * 100)))
          .tap(_ => sleep(200.millis))
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1).forall(s => durationMs(s) >= 150 && durationMs(s) < 350))

      "stack the buffers of nested spanStages, so the outer buffer isolates the inner span" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        given BufferCapacity = BufferCapacity(1)
        flow
          .spanStages(myTracing)(s"$prefix-$Level1")(f =>
            given BufferCapacity = BufferCapacity(0)
            f.spanStages(myTracing)(s"$prefix-$Level1_First")(_.mapConcat(y => List(y, y * 10, y * 100)))
          )
          .tap(_ => sleep(200.millis))
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 3)
        assert(spansOf(Level1_First).forall(durationMs(_) < 150))

      "count the time of the following stages in the inner span when the outer buffer is 0 too" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        extension [T](flow: Flow[T]) def traced[U](name: String)(f: Flow[T] => Flow[U]) = flow.spanStages(myTracing)(name)(f)

        given BufferCapacity = BufferCapacity(0)
        flow
          .spanStages(myTracing)(s"$prefix-$Level1"):
            _.tap(println).spanStages(myTracing)(s"$prefix-$Level1_First"):
              _.mapConcat(y => List(y, y * 10, y * 100))
          .tap(_ => sleep(200.millis))
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 3)
        assert(spansOf(Level1_First).forall(s => durationMs(s) >= 150 && durationMs(s) < 350))

      "not count the time of the following stages when the inner flow fits in the buffer" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        given BufferCapacity = BufferCapacity(2)
        flow.spanStages(myTracing)(s"$prefix-$Level1")(_.mapConcat(x => List(x, x * 10))).tap(_ => sleep(200.millis)).runToList(): Unit

        parent.close()

        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1).forall(durationMs(_) < 150))

      "not mark the spans as failed when a following stage fails" in new Fixture:
        try
          flow
            .spanStages(myTracing)(s"$prefix-$Level0")(identity)
            .map { case 2 => throw new Exception("boom"); case x => x }
            .runToList(): Unit
        catch case _ => ()

        assert(spansOf(Level0).nonEmpty)
        assert(spansOf(Level0).forall(_.getStatus.getStatusCode == StatusCode.UNSET))
        assert(spansOf(Level0).forall(_.getEvents.isEmpty))

      "mark only the span of the inner flow that fails" in new Fixture:
        try
          flow
            .spanStages(myTracing)(s"$prefix-$Level0")(_.map { case 2 => throw new Exception("boom"); case y => y })
            .runToList(): Unit
        catch case _ => ()

        // the flow is stopped by the failure of the second inner flow, so there is no third one
        assert(spansOf(Level0).size == 2)
        assert(spansOf(Level0).count(_.getStatus.getStatusCode == StatusCode.ERROR) == 1)

      "work with infinite inner flows" in new Fixture:
        val result = flow.spanStages(myTracing)(s"$prefix-$Level0")(_.flatMap(_ => Flow.iterate(1)(_ + 1))).take(5).runToList()

        assert(result == List(1, 2, 3, 4, 5))
        assert(spansOf(Level0).size == 1)
        // the inner flow is cancelled once the downstream is done, which isn't a failure
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "run the inner flows one after another" in new Fixture:
        val events = new java.util.concurrent.ConcurrentLinkedQueue[String]()

        flow
          .spanStages(myTracing)(s"$prefix-$Level0")(
            _.flatMap(x =>
              Flow.usingEmit[Int] { emit =>
                events.add(s"start $x")
                emit(x)
                events.add(s"end $x")
              }
            )
          )
          .tap(x => events.add(s"got $x"))
          .runToList(): Unit

        val log = scala.jdk.CollectionConverters.CollectionHasAsScala(events).asScala.toList
        // the inner flow of an element starts only after the previous one is done, and its elements are consumed
        List(1, 2).foreach { x =>
          assert(log.indexOf(s"end $x") < log.indexOf(s"start ${x + 1}"))
          assert(log.indexOf(s"got $x") < log.indexOf(s"start ${x + 1}"))
        }
    }

    "spanStages" should {
      def durationMs(span: io.opentelemetry.sdk.trace.data.SpanData) = (span.getEndEpochNanos - span.getStartEpochNanos) / 1000000

      "open a span per element, covering the stages on both sides of a buffer created by the function" in new Fixture:
        flow
          .spanStages(myTracing)(s"$prefix-$Level1")(_.tap(_ => sleep(200.millis)).buffer().tap(_ => sleep(300.millis)))
          .tap(_ => sleep(600.millis))
          .runToList(): Unit

        val spans = spansOf(Level1).sortBy(_.getStartEpochNanos)
        assert(spans.size == 3)
        spans.foreach(s => assertAbout(durMs(s), 500))
        spans.foreach(s => assert(!spans.exists(_.getSpanId == s.getParentSpanId)))

      "open spans created in the body of the function as children of the span of the element" in new Fixture:
        flow
          .spanStages(myTracing)(s"$prefix-$Level1")(f =>
            (()).span(myTracing)(s"$prefix-$Level1_First")
            f
          )
          .runToList(): Unit

        val parents = spansOf(Level1).map(_.getSpanId).toSet
        assert(parents.size == 3)
        assert(spansOf(Level1_First).size == 3 && spansOf(Level1_First).forall(c => parents.contains(c.getParentSpanId)))

      "open spans created by the stages as children of the span of the element" in new Fixture:
        flow
          .spanStages(myTracing)(s"$prefix-$Level1")(_.map(x => x.span(myTracing)(s"$prefix-$Level1_First")).buffer())
          .runToList(): Unit

        val parents = spansOf(Level1).map(_.getSpanId).toSet
        assert(spansOf(Level1_First).size == 3 && spansOf(Level1_First).forall(c => parents.contains(c.getParentSpanId)))

      "record the exception thrown by the function on the span of the element" in new Fixture:
        val thrown = intercept[Exception]:
          flow.spanStages(myTracing)(s"$prefix-$Level1")(_ => throw new Exception("boom")).runToList(): Unit

        assert(thrown.getMessage.contains("boom"))
        val span = spansOf(Level1).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "boom")
        assert(span.getEvents.asScala.exists(_.getName == "exception"))

      "record the exception thrown by a stage of the function on the span of the element only" in new Fixture:
        intercept[Exception]:
          flow
            .spanStages(myTracing)(s"$prefix-$Level1")(_.map { case 2 => throw new Exception("boom"); case x => x })
            .runToList(): Unit

        // the flow is stopped by the second element, so there is no third span
        val spans = spansOf(Level1).sortBy(_.getStartEpochNanos)
        assert(spans.size == 2)
        assert(spans.head.getStatus.getStatusCode == StatusCode.UNSET)
        assert(spans(1).getStatus.getStatusCode == StatusCode.ERROR)
        assert(spans(1).getStatus.getDescription == "boom")

      "record the exception thrown by a stage after a buffer created by the function on the span of the element" in new Fixture:
        intercept[Exception]:
          flow
            .spanStages(myTracing)(s"$prefix-$Level1")(_.buffer().map { case 2 => throw new Exception("boom"); case x => x })
            .runToList(): Unit

        val spans = spansOf(Level1).sortBy(_.getStartEpochNanos)
        assert(spans.size == 2)
        assert(spans.head.getStatus.getStatusCode == StatusCode.UNSET)
        assert(spans(1).getStatus.getStatusCode == StatusCode.ERROR)
        assert(spans(1).getStatus.getDescription == "boom")

      "not record the exception thrown by a stage after the function on the span" in new Fixture:
        intercept[Exception]:
          flow
            .spanStages(myTracing)(s"$prefix-$Level1")(_.map(identity))
            .map { case 2 => throw new Exception("boom"); case x => x }
            .runToList(): Unit

        assert(spansOf(Level1).nonEmpty)
        assert(spansOf(Level1).forall(_.getStatus.getStatusCode == StatusCode.UNSET))

      "not count the time of the following stages, as the element is handed over once" in new Fixture:
        flow.spanStages(myTracing)(s"$prefix-$Level1")(_.map(_ + 1)).tap(_ => sleep(200.millis)).runToList(): Unit

        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1).forall(durationMs(_) < 150))
    }

    "cancelled" should {
      "end span when enclosing supervised scope fails before stream completion" in new Fixture:
        val semaphore = Semaphore(0)

        try
          supervised {
            forkUser {
              semaphore.acquire()
              throw new Exception("oops")
            }
            forkUser {
              Flow
                .usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level0"))
                .buffer()
                .tap { case 1 => semaphore.release(); case _ => sleep(100.seconds) }
                .runToList()
            }
          }
        catch case err => ()
        end try

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assertNesting()

      "end span when the enclosing fork is cancelled" in new Fixture:
        val semaphore = Semaphore(0)

        supervised {
          val forkWithSpan = forkCancellable {
            Flow
              .usingEmit[Int](emit => flow.runToEmit(emit).span(myTracing)(s"$prefix-$Level0"))
              .buffer()
              .tap { case 1 => semaphore.release(); case _ => sleep(100.seconds) }
              .runToList()
          }
          semaphore.acquire()
          forkWithSpan.cancelNow()
        }

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)
        assert(spansOf(Level0).head.getEvents.isEmpty)
        assertNesting()

      "not mark the span as failed when the code exits with a boundary break" in new Fixture:
        val result = scala.util.boundary:
          scala.util.boundary.break(1).span(myTracing)(s"$prefix-$Level0")
          2

        assert(result == 1)
        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)
        assert(spansOf(Level0).head.getEvents.isEmpty)

      "not pass a boundary break, a control throwable or an interruption to beforeClose" in new Fixture:
        var seen: List[Option[Throwable]] = Nil
        def record(s: SpanWithError[Nothing]) =
          seen = s.defectOpt :: seen
          s.span

        scala.util.boundary:
          scala.util.boundary.break(1).span(myTracing)(s"$prefix-$Level0", beforeClose = record)
        intercept[scala.util.control.ControlThrowable]:
          (throw new scala.util.control.ControlThrowable {}).span(myTracing)(s"$prefix-$Level0", beforeClose = record)
        intercept[InterruptedException]:
          (throw new InterruptedException()).span(myTracing)(s"$prefix-$Level0", beforeClose = record)

        assert(seen == List(None, None, None))
        assert(spansOf(Level0).size == 3)
        assert(spansOf(Level0).forall(_.getStatus.getStatusCode == StatusCode.UNSET))

      "treat an interruption during I/O as not a failure" in new Fixture:
        var seen: Option[Throwable] = Some(new Exception("unset"))
        intercept[java.nio.channels.ClosedByInterruptException]:
          (throw new java.nio.channels.ClosedByInterruptException()).span(myTracing)(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s.defectOpt
              s.span
          )

        assert(seen.isEmpty)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "record the closing of a channel by another thread as a failure" in new Fixture:
        intercept[java.nio.channels.AsynchronousCloseException]:
          (throw new java.nio.channels.AsynchronousCloseException()).span(myTracing)(s"$prefix-$Level0")

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "treat an ExecutionException wrapping an interruption as not a failure" in new Fixture:
        var seen: Option[Throwable] = Some(new Exception("unset"))
        intercept[java.util.concurrent.ExecutionException]:
          (throw new java.util.concurrent.ExecutionException(new InterruptedException())).span(myTracing)(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s.defectOpt
              s.span
          )

        assert(seen.isEmpty)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "treat an ExecutionException wrapping a failure as a failure" in new Fixture:
        intercept[java.util.concurrent.ExecutionException]:
          (throw new java.util.concurrent.ExecutionException(new Exception("boom"))).span(myTracing)(s"$prefix-$Level0")

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "record fatal errors as failures" in new Fixture:
        intercept[LinkageError]:
          (throw new LinkageError("broken")).span(myTracing)(s"$prefix-$Level0")

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "pass a failure to beforeClose" in new Fixture:
        var seen: Option[Throwable] = None
        intercept[Exception]:
          (throw new Exception("boom")).span(myTracing)(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s.defectOpt
              s.span
          )

        assert(seen.exists(_.getMessage == "boom"))

      "add the exception of beforeClose as suppressed to an interruption, which is still thrown" in new Fixture:
        val interruption = new InterruptedException()

        val thrown = intercept[InterruptedException]:
          (throw interruption).span(myTracing)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("from beforeClose"))

        assert(thrown eq interruption)
        assert(thrown.getSuppressed.exists(_.getMessage == "from beforeClose"))
        assert(spansOf(Level0).size == 1)
    }
  }
end OxTelemetryTest
