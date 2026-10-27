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
import ox.either.ok
import ox.flow.Flow
import ox.otel.context.PropagatingVirtualThreadFactory
import ox.telemetry.tracing.*

import java.util.UUID
import java.util.concurrent.Semaphore
import scala.collection.JavaConverters.asScalaBufferConverter
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
        Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level0")(flow.runToEmit(emit))).buffer(): Unit
        assert(testSpans.isEmpty)
        assertNesting()

      "open a single span for a multi-element flow" in new Fixture:
        Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level0")(flow.runToEmit(emit))).buffer().runDrain()

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assertNesting()

      "handle buffer one span per span() call when spans are nested" in new Fixture:
        supervised {

          val spanned1 = Flow
            .usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(flow.runToEmit(emit)))
            .buffer()
            .map(_ + 100)
            .tap(i => currentSpanUnsafe().addEvent(i.toString))

          Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level0")(spanned1.runToEmit(emit))).buffer().runDrain()

          assert(spansOf(Level0).size == 1)
          assert(spansOf(Level1).size == 1)
          assert(testSpans.size == 2)
          assertNesting()
        }
      "open one span per span() call when spans are nested" in new Fixture:
        supervised {
          val spanned1 =
            (Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(flow.runToEmit(emit))).buffer() ++ Flow.fromValues(4, 2))
              .tap(i => currentSpanUnsafe().addEvent(i.toString))

          Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level0")(spanned1.runToEmit(emit))).buffer().runDrain()

          assert(spansOf(Level0).size == 1)
          assert(spansOf(Level1).size == 1)
          assert(testSpans.size == 2)
          assertNesting()
        }

      "open a single span when downstream uses mapPar" in new Fixture:
        Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level0")(flow.runToEmit(emit))).buffer().mapPar(3)(_ + 1).runDrain()

        assert(testSpans.size == 1)

        val only = spansOf(Level0).head
        assert(only.getStatus.getStatusCode == StatusCode.UNSET)
        assert(only.getEvents.isEmpty)
        assertNesting()
    }

    "used under parent spans" should {
      "use the current span as parent" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(flow.runToEmit(emit))).buffer().runToList(): Unit

        parent.close()

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 1)
        assertNesting()

      "open one child span per element when flatMapped under a parent span" in new Fixture:
        val spanned = Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(flow.runToEmit(emit))).buffer()

        Flow
          .usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level0")(flow.flatMap { _ => spanned }.runToEmit(emit)))
          .buffer()
          .runToList(): Unit

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 3)
        assertNesting()

      "parent spans of concurrently run flows under the enclosing span" in new Fixture:
        val spanned = Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(flow.runToEmit(emit))).buffer()

        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        supervised {
          (1 to 10).foreach(_ => fork(spanned.runToList()))
        }

        parent.close()

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 10)
        assertNesting()

      "nest spans across forks and inner supervised scopes" in new Fixture:
        val spanned = Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(flow.runToEmit(emit))).buffer()

        span(myTracing)(s"$prefix-$Level0")(supervised {
          (1 to 10).map {
            case 1 =>
              forkUser(
                Flow
                  .usingEmit[Int](emit =>
                    span(myTracing)(s"$prefix-$Level1_First")(
                      flow
                        .flatMap { el =>
                          val innerFlow = supervised {
                            Flow
                              .usingEmit[Int](emit =>
                                span(myTracing)(
                                  s"$prefix-$Level1_First_Child",
                                  beforeClose = _.span.addEvent("boomed")
                                )(
                                  flow
                                    .runToEmit(emit)
                                )
                              )
                              .buffer()
                          }
                          innerFlow
                        }
                        .runToEmit(emit)
                    )
                  )
                  .buffer()
                  .runToList()
              )

            case _ => forkUser(spanned.runToList())
          }
        })

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
              span(myTracing)(
                s"$prefix-$Level0",
                beforeClose = { case SpanWithError(s, thr, _) =>
                  s.addEvent(thr.toString)
                }
              )(
                flow
                  .tap { case 2 => throw new Exception("boom"); case _ => () }
                  .runToEmit(emit)
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
        val spanned = Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(flow.runToEmit(emit))).buffer()

        Flow
          .usingEmit[Int](emit =>
            span(myTracing)(s"$prefix-$Level0")(
              spanned
                .map { case 2 => throw new Exception("boom"); case x => x }
                .recover(_ => 5)
                .runToEmit(emit)
            )
          )
          .buffer()
          .runToList(): Unit

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 1)
        assertNesting()

      "end spans of concurrent flows when one of them fails" in new Fixture:
        val spanned = Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(flow.runToEmit(emit))).buffer()

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

      "end the spans of nested flows, adding events to them, when a nested flow fails with a break" in new Fixture:
        val em = EitherMode[String]

        val level0 = spanUnsafe(myTracing)(s"$prefix-$Level0")

        val spanned = Flow.usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(flow.runToEmit(emit))).buffer()

        try
          supervised {
            (1 to 10).map {
              case 1 =>
                forkUser {
                  Flow
                    .usingEmit[Int](emit =>
                      span(myTracing)(s"$prefix-$Level1_First")(
                        flow
                          .flatMap { el =>
                            {
                              val inner = supervisedError(em):
                                either:
                                  val innerFlow =
                                    em.pure(
                                      Flow
                                        .usingEmit[Int](emit =>
                                          span(myTracing)(
                                            s"$prefix-$Level1_First_Child",
                                            beforeClose = _.span.addEvent(s"boomed at $el")
                                          )(
                                            flow
                                              .runToEmit(emit)
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
                          .runToEmit(emit)
                      )
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
        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1_First).size == 1)
        assert(spansOf(Level1_First_Child).size == 1) // because flow.flatMap is evaluated sequentially and exception ends whole flow
        assert(spansOf(Level1_First_Child).head.getEvents.asScala.exists(_.getName == "boomed at 1"))
        assertNesting()

      "record the error returned by a span in supervisedError, ending the span, and continue with the following elements" in new Fixture:
        val em = EitherMode[String]

        val result = flow
          .flatMap: el =>
            supervisedError(em):
              spanError(myTracing, em)(s"$prefix-$Level1")(
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

    "used with async stages downstream" should {
      "make a span after buffer a sibling of the whole-run span" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        Flow
          .usingEmit[Int](emit =>
            span(myTracing)(s"$prefix-$Level1")(
              flow
                .runToEmit(emit)
            )
          )
          .buffer()
          .buffer()
          .map(x => span(myTracing)(s"$prefix-$Level1_Stage")(x))
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
          Flow.usingEmit[String](emit => span(myTracing)(s"$prefix-$Level1_First")(readLines(file).runToEmit(emit))).buffer()

        (for
          file <- Flow.fromIterable(filesToDownload).map(file => span(myTracing)(s"$prefix-$Level1")(download(file)))
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
            .usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1")(Flow.fromValues(x).tap(_ => sleep(a)).runToEmit(emit)))
            .buffer()
          z <- Flow
            .usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level1_First")(Flow.fromValues(y).tap(_ => sleep(b)).runToEmit(emit)))
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
    }

    "spanEach" should {
      "open a span per element, around the stages placed after it" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .spanEach(myTracing)(s"$prefix-$Level1_First")
          .map(x => span(myTracing)(s"$prefix-$Level1_First_Child")(x))
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 3)
        assert(spansOf(Level1_First_Child).size == 3)
        assertNesting()

      "not cover the stages placed before it" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .map(x => span(myTracing)(s"$prefix-$Level1")(x))
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
        val result = spanError(myTracing, em)(s"$prefix-$Level0")(Right(1))

        assert(result == Right(1))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.UNSET)
        assert(span.getEvents.isEmpty)

      "record an application error on the span and return it as is" in new Fixture:
        val result = spanError(myTracing, em)(s"$prefix-$Level0")(Left("bad"): Either[String, Int])

        assert(result == Left("bad"))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "bad")
        val event = span.getEvents.asScala.find(_.getName == "exception").get
        assert(event.getAttributes.get(AttributeKey.stringKey("exception.message")) == "bad")
        assert(event.getAttributes.get(AttributeKey.stringKey("exception.type")) == "java.lang.String")

      "record an application error which is an exception as an exception" in new Fixture:
        val result = spanError(myTracing, EitherMode[Exception]())(
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

        spanError(myTracing, em)(
          s"$prefix-$Level0",
          beforeClose = s =>
            seen = s
            s.span
        )(Left("bad"): Either[String, Int]): Unit

        assert(seen.nn.errorOpt == Some("bad"))
        assert(seen.nn.defectOpt.isEmpty)

      "record a thrown exception and pass it to beforeClose" in new Fixture:
        var seen: SpanWithError[String] | Null = null

        val thrown = intercept[Exception]:
          spanError(myTracing, em)(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s
              s.span
          )((throw new Exception("boom")): Either[String, Int]): Unit

        assert(thrown.getMessage == "boom")
        assert(seen.nn.defectOpt.map(_.getMessage) == Some("boom"))
        assert(seen.nn.errorOpt.isEmpty)
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "boom")

      "record an application error short-circuited with ok, when spanError is placed around the either block" in new Fixture:
        val result = spanError(myTracing, em)(s"$prefix-$Level0")(either {
          val x: Int = (Left("bad"): Either[String, Int]).ok()
          x
        })

        assert(result == Left("bad"))
        val span = spansOf(Level0).head
        assert(span.getStatus.getStatusCode == StatusCode.ERROR)
        assert(span.getStatus.getDescription == "bad")

      "return the application error, and close the span, when beforeClose throws" in new Fixture:
        val result =
          spanError(myTracing, em)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("cb"))(Left("bad"): Either[String, Int])

        assert(result == Left("bad"))
        assert(spansOf(Level0).size == 1)

      "throw from beforeClose, and close the span, when t succeeds" in new Fixture:
        val thrown = intercept[Exception]:
          spanError(myTracing, em)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("cb"))(Right(1): Either[String, Int])

        assert(thrown.getMessage == "cb")
        assert(spansOf(Level0).size == 1)
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
                .usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level0")(flow.runToEmit(emit)))
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
              .usingEmit[Int](emit => span(myTracing)(s"$prefix-$Level0")(flow.runToEmit(emit)))
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
          span(myTracing)(s"$prefix-$Level0")(scala.util.boundary.break(1))
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
          span(myTracing)(s"$prefix-$Level0", beforeClose = record)(scala.util.boundary.break(1))
        intercept[scala.util.control.ControlThrowable]:
          span(myTracing)(s"$prefix-$Level0", beforeClose = record)(throw new scala.util.control.ControlThrowable {})
        intercept[InterruptedException]:
          span(myTracing)(s"$prefix-$Level0", beforeClose = record)(throw new InterruptedException())

        assert(seen == List(None, None, None))
        assert(spansOf(Level0).size == 3)
        assert(spansOf(Level0).forall(_.getStatus.getStatusCode == StatusCode.UNSET))

      "treat an interruption during I/O as not a failure" in new Fixture:
        var seen: Option[Throwable] = Some(new Exception("unset"))
        intercept[java.nio.channels.ClosedByInterruptException]:
          span(myTracing)(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s.defectOpt
              s.span
          )(throw new java.nio.channels.ClosedByInterruptException())

        assert(seen.isEmpty)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "record the closing of a channel by another thread as a failure" in new Fixture:
        intercept[java.nio.channels.AsynchronousCloseException]:
          span(myTracing)(s"$prefix-$Level0")(throw new java.nio.channels.AsynchronousCloseException())

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "treat an ExecutionException wrapping an interruption as not a failure" in new Fixture:
        var seen: Option[Throwable] = Some(new Exception("unset"))
        intercept[java.util.concurrent.ExecutionException]:
          span(myTracing)(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s.defectOpt
              s.span
          )(throw new java.util.concurrent.ExecutionException(new InterruptedException()))

        assert(seen.isEmpty)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "treat an ExecutionException wrapping a failure as a failure" in new Fixture:
        intercept[java.util.concurrent.ExecutionException]:
          span(myTracing)(s"$prefix-$Level0")(throw new java.util.concurrent.ExecutionException(new Exception("boom")))

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "record fatal errors as failures" in new Fixture:
        intercept[LinkageError]:
          span(myTracing)(s"$prefix-$Level0")(throw new LinkageError("broken"))

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "pass a failure to beforeClose" in new Fixture:
        var seen: Option[Throwable] = None
        intercept[Exception]:
          span(myTracing)(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s.defectOpt
              s.span
          )(throw new Exception("boom"))

        assert(seen.exists(_.getMessage == "boom"))

      "add the exception of beforeClose as suppressed to an interruption, which is still thrown" in new Fixture:
        val interruption = new InterruptedException()

        val thrown = intercept[InterruptedException]:
          span(myTracing)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("from beforeClose"))(throw interruption)

        assert(thrown eq interruption)
        assert(thrown.getSuppressed.exists(_.getMessage == "from beforeClose"))
        assert(spansOf(Level0).size == 1)
    }
  }
end OxTelemetryTest
