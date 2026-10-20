package ox.telemetry

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
import ox.channels.BufferCapacity
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

  val myTracing: OxTracing = OxTracing(tracer)

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
        flow.span(myTracing)(s"$prefix-$Level0"): Unit
        assert(testSpans.isEmpty)
        assertNesting()

      "open a single span for a multi-element flow" in new Fixture:
        flow.span(myTracing)(s"$prefix-$Level0").runDrain()

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assertNesting()

      "handle buffer one span per span() call when spans are nested" in new Fixture:
        supervised {

          val spanned1 = flow.span(myTracing)(s"$prefix-$Level1").map(_ + 100).tap(i => currentSpanUnsafe(myTracing).addEvent(i.toString))

          spanned1.span(myTracing)(s"$prefix-$Level0").runDrain()

          assert(spansOf(Level0).size == 1)
          assert(spansOf(Level1).size == 1)
          assert(testSpans.size == 2)
          assertNesting()
        }
      "open one span per span() call when spans are nested" in new Fixture:
        supervised {
          val spanned1 =
            (flow.span(myTracing)(s"$prefix-$Level1") ++ Flow.fromValues(4, 2)).tap(i => currentSpanUnsafe(myTracing).addEvent(i.toString))

          spanned1.span(myTracing)(s"$prefix-$Level0").runDrain()

          assert(spansOf(Level0).size == 1)
          assert(spansOf(Level1).size == 1)
          assert(testSpans.size == 2)
          assertNesting()
        }

      "open a single span when downstream uses mapPar" in new Fixture:
        flow.span(myTracing)(s"$prefix-$Level0").mapPar(3)(_ + 1).runDrain()

        assert(testSpans.size == 1)

        val only = spansOf(Level0).head
        assert(only.getStatus.getStatusCode == StatusCode.UNSET)
        assert(only.getEvents.isEmpty)
        assertNesting()
    }

    "used under parent spans" should {
      "use the current span as parent" in new Fixture:
        val parent = myTracing.spanUnsafe(s"$prefix-$Level0")

        flow.span(myTracing)(s"$prefix-$Level1").runToList(): Unit

        parent.close()

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 1)
        assertNesting()

      "open one child span per element when flatMapped under a parent span" in new Fixture:
        val spanned = flow.span(myTracing)(s"$prefix-$Level1")

        flow.flatMap { _ => spanned }.span(myTracing)(s"$prefix-$Level0").runToList(): Unit

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 3)
        assertNesting()

      "parent spans of concurrently run flows under the enclosing span" in new Fixture:
        val spanned = flow.span(myTracing)(s"$prefix-$Level1")

        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        supervised {
          (1 to 10).foreach(_ => fork(spanned.runToList()))
        }

        parent.close()

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 10)
        assertNesting()

      "nest spans across forks and inner supervised scopes" in new Fixture:
        val spanned = flow.span(myTracing)(s"$prefix-$Level1")

        myTracing.byNameSpan(supervised {
          (1 to 10).map {
            case 1 =>
              forkUser(
                flow
                  .flatMap { el =>
                    val innerFlow = supervised {
                      flow.span(myTracing)(s"$prefix-$Level1_First_Child", beforeClose = _._1.addEvent("boomed"))
                    }
                    innerFlow
                  }
                  .span(myTracing)(s"$prefix-$Level1_First")
                  .runToList()
              )

            case _ => forkUser(spanned.runToList())
          }
        })(s"$prefix-$Level0")

        assert(spansOf(Level1).size == 9)
        assert(spansOf(Level1_First).size == 1)
        assert(spansOf(Level1_First_Child).size == 3)

        assertNesting()
    }

    "a stage fails" should {
      "record exception and set ERROR status when the flow fails" in new Fixture:
        try
          flow
            .tap { case 2 => throw new Exception("boom"); case _ => () }
            .span(myTracing)(
              s"$prefix-$Level0",
              beforeClose = { case (s, thr) =>
                s.addEvent(thr.toString)
              }
            )
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
        val spanned = flow.span(myTracing)(s"$prefix-$Level1")

        spanned
          .map { case 2 => throw new Exception("boom"); case x => x }
          .recover(_ => 5)
          .span(myTracing)(s"$prefix-$Level0")
          .runToList(): Unit

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 1)
        assertNesting()

      "end spans of concurrent flows when one of them fails" in new Fixture:
        val spanned = flow.span(myTracing)(s"$prefix-$Level1")

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

        val spanned = flow.span(myTracing)(s"$prefix-$Level1")

        try
          supervised {
            (1 to 10).map {
              case 1 =>
                forkUser {
                  flow
                    .flatMap { el =>
                      {
                        val inner = supervisedError(em):
                          either:
                            val innerFlow =
                              em.pure(
                                flow.span(myTracing)(s"$prefix-$Level1_First_Child", beforeClose = _._1.addEvent(s"boomed at $el")).map {
                                  case 1 => em.pureError("ops").ok();
                                  case x => x
                                }
                              )
                            innerFlow.ok()
                        inner
                      }.toOption.get
                    }
                    .span(myTracing)(s"$prefix-$Level1_First")
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

        val spanned = flow.span(myTracing)(s"$prefix-$Level1")

        try
          supervised {
            (1 to 10).map {
              case 1 =>
                forkUser {
                  flow
                    .flatMap { el =>
                      {
                        val inner = supervisedError(em):
                          either:
                            val innerFlow =
                              em.pure(
                                flow.span(myTracing)(s"$prefix-$Level1_First_Child", beforeClose = _._1.addEvent(s"boomed at $el")).map {
                                  case 1 => em.pureError("ops").ok();
                                  case x => x
                                }
                              )
                            innerFlow.ok()
                        inner
                      }.toOption.getOrElse(Flow.empty)
                    }
                    .span(myTracing)(s"$prefix-$Level1_First")
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

        val spanned = flow.span(myTracing)(s"$prefix-$Level1")

        try
          supervisedError(em) {
            either:
              em.pure:
                (1 to 10).map {
                  case 1 =>
                    forkUser {
                      flow
                        .flatMap { el =>
                          {
                            supervisedError(em):
                              val innerFlow =
                                em.pure(
                                  flow.span(myTracing)(s"$prefix-$Level1_First_Child", beforeClose = _._1.addEvent(s"boomed at $el")).map {
                                    case 1 => em.pureError("ops").ok();
                                    case x => x
                                  }
                                )
                              innerFlow
                          }.toOption.get
                        }
                        .span(myTracing)(s"$prefix-$Level1_First")
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

        flow
          .span(myTracing)(s"$prefix-$Level1")
          .buffer()
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage"))
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

      "open a span per file with mapSpanned, covering only the download" in new Fixture:
        val filesToDownload = Seq(1, 2)

        def download(file: Int): Int =
          sleep(a)
          file

        def readLines(file: Int): Flow[String] = Flow.fromValues(s"line of $file").tap(_ => sleep(b))

        def streamLinesFlow(file: Int) = readLines(file).span(myTracing)(s"$prefix-$Level1_First")

        (for
          file <- Flow.fromIterable(filesToDownload).mapSpanned(myTracing)(s"$prefix-$Level1")(download)
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
          y <- Flow.fromValues(x).tap(_ => sleep(a)).span(myTracing)(s"$prefix-$Level1")
          z <- Flow.fromValues(y).tap(_ => sleep(b)).span(myTracing)(s"$prefix-$Level1_First")
        yield z)
          .tap(_ => sleep(c))
          .runToList(): Unit

        val y = spansOf(Level1).head
        val z = spansOf(Level1_First).head
        assert(z.getParentSpanId != y.getSpanId)
        assertAbout(durMs(y), aMs)
        assertAbout(durMs(z), bMs)
        assertAbout(startOffsetMs(y, z), aMs)

      "nest the durations of a spannedAsync in a flatMapSpanned" in new Fixture:
        Flow
          .fromValues(1)
          .flatMapSpanned(myTracing)(s"$prefix-$Level1")(x =>
            for
              y <- Flow.fromValues(x).tap(_ => sleep(a))
              z <- Flow.fromValues(y).tap(_ => sleep(b)).span(myTracing)(s"$prefix-$Level1_First")
            yield z
          )
          .tap(_ => sleep(c))
          .runToList(): Unit

        val parent = spansOf(Level1).head
        val child = spansOf(Level1_First).head
        assert(child.getParentSpanId == parent.getSpanId)
        assertAbout(durMs(parent), aMs + bMs)
        assertAbout(durMs(child), bMs)
        assertAbout(startOffsetMs(parent, child), aMs)
        assert(child.getEndEpochNanos <= parent.getEndEpochNanos)

      "nest the spans and durations of a flatMapSpanned inside a flatMapSpanned" in new Fixture:
        Flow
          .fromValues(1)
          .flatMapSpanned(myTracing)(s"$prefix-$Level1")(x =>
            Flow
              .fromValues(x)
              .tap(_ => sleep(a))
              .flatMapSpanned(myTracing)(s"$prefix-$Level1_First")(y => Flow.fromValues(y).tap(_ => sleep(b)))
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

      "make chained flatMapSpanned spans siblings, each covering only its own flow" in new Fixture:
        Flow
          .fromValues(1)
          .flatMapSpanned(myTracing)(s"$prefix-$Level1")(x => Flow.fromValues(x).tap(_ => sleep(a)))
          .flatMapSpanned(myTracing)(s"$prefix-$Level1_First")(y => Flow.fromValues(y).tap(_ => sleep(b)))
          .tap(_ => sleep(c))
          .runToList(): Unit

        val first = spansOf(Level1).head
        val second = spansOf(Level1_First).head
        assert(second.getParentSpanId != first.getSpanId)
        assertAbout(durMs(first), aMs)
        assertAbout(durMs(second), bMs)
        assertAbout(startOffsetMs(first, second), aMs)

    }

    "mapSpanned" should {
      "open a span around each call of the mapping function only" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .mapSpanned(myTracing)(s"$prefix-$Level1_First")(x => myTracing.byNameSpan(x + 1)(s"$prefix-$Level1_First_Child"))
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1"))
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 3)
        // created by the function, so they are children of the per-element spans
        assert(spansOf(Level1_First_Child).size == 3)
        // created by the following stage, after the per-element spans were closed, so they are siblings
        assert(spansOf(Level1).size == 3)
        assertNesting()

      "mark the span as failed when the mapping function fails" in new Fixture:
        try flow.mapSpanned(myTracing)(s"$prefix-$Level0") { case 2 => throw new Exception("boom"); case x => x }.runToList(): Unit
        catch case _ => ()

        assert(spansOf(Level0).size == 2)
        assert(spansOf(Level0).count(_.getStatus.getStatusCode == StatusCode.ERROR) == 1)
    }

    "flatMapSpanned" should {
      def durationMs(span: io.opentelemetry.sdk.trace.data.SpanData) = (span.getEndEpochNanos - span.getStartEpochNanos) / 1000000

      "open spans created in the body of the function, before it returns the flow, as children of the span" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .flatMapSpanned(myTracing)(s"$prefix-$Level1_First")(x =>
            myTracing.byNameSpan(())(s"$prefix-$Level1_First_Child")
            Flow.fromValues(x).map(y => myTracing.byNameSpan(y)(s"$prefix-$Level1_Stage"))
          )
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
          .flatMapSpanned(myTracing)(s"$prefix-$Level1_First")(x =>
            inF.add(Thread.currentThread()): Unit
            Flow.fromValues(x).tap(_ => inFlow.add(Thread.currentThread()): Unit)
          )
          .runToList(): Unit

        assert(inF.size == 3 && inF.toArray.forall(_ ne caller))
        assert(inFlow.size == 3 && inFlow.toArray.forall(_ ne caller))

      "record the exception thrown by the function before it returns the flow on the span of the element" in new Fixture:
        val thrown = intercept[Exception]:
          flow
            .flatMapSpanned(myTracing)(s"$prefix-$Level1_First")(x => if x == 2 then throw new Exception("boom") else Flow.fromValues(x))
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

        assert(flow.flatMapSpanned(myTracing)(s"$prefix-$Level1_First")(f).runToList() == flow.flatMap(f).runToList())

      "open a span per element, with the spans created in the inner flows as children and the following ones as siblings" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .flatMapSpanned(myTracing)(s"$prefix-$Level1_First")(x =>
            Flow.fromValues(1 to x*).map(y => myTracing.byNameSpan(y)(s"$prefix-$Level1_First_Child"))
          )
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1"))
          .runToList(): Unit

        parent.close()

        // elements 1, 2, 3 are flatMapped to 1, 2, 3 outputs
        assert(spansOf(Level1_First).size == 3)
        assert(spansOf(Level1_First_Child).size == 6)
        assert(spansOf(Level1).size == 6)
        assertNesting()

      "emit the elements of the inner flows in order" in new Fixture:
        val result = flow.flatMapSpanned(myTracing)(s"$prefix-$Level0")(x => Flow.fromValues(x, x * 10)).runToList()

        assert(result == List(1, 10, 2, 20, 3, 30))
        assert(spansOf(Level0).size == 3)

      "not count the time of the following stages" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow.flatMapSpanned(myTracing)(s"$prefix-$Level1")(x => Flow.fromValues(x)).tap(_ => sleep(200.millis)).runToList(): Unit

        parent.close()

        // each inner flow is done quickly, while the following stage takes 200ms per element
        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1).forall(durationMs(_) < 150))

      "not mark the spans as failed when a following stage fails" in new Fixture:
        try
          flow
            .flatMapSpanned(myTracing)(s"$prefix-$Level0")(x => Flow.fromValues(x))
            .map { case 2 => throw new Exception("boom"); case x => x }
            .runToList(): Unit
        catch case _ => ()

        assert(spansOf(Level0).nonEmpty)
        assert(spansOf(Level0).forall(_.getStatus.getStatusCode == StatusCode.UNSET))
        assert(spansOf(Level0).forall(_.getEvents.isEmpty))

      "mark only the span of the inner flow that fails" in new Fixture:
        try
          flow
            .flatMapSpanned(myTracing)(s"$prefix-$Level0")(x =>
              Flow.fromValues(x).map { case 2 => throw new Exception("boom"); case y => y }
            )
            .runToList(): Unit
        catch case _ => ()

        // the flow is stopped by the failure of the second inner flow, so there is no third one
        assert(spansOf(Level0).size == 2)
        assert(spansOf(Level0).count(_.getStatus.getStatusCode == StatusCode.ERROR) == 1)

      "work with infinite inner flows" in new Fixture:
        val result = flow.flatMapSpanned(myTracing)(s"$prefix-$Level0")(_ => Flow.iterate(1)(_ + 1)).take(5).runToList()

        assert(result == List(1, 2, 3, 4, 5))
        assert(spansOf(Level0).size == 1)
        // the inner flow is cancelled once the downstream is done, which isn't a failure
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "run the inner flows one after another" in new Fixture:
        val events = new java.util.concurrent.ConcurrentLinkedQueue[String]()

        flow
          .flatMapSpanned(myTracing)(s"$prefix-$Level0")(x =>
            Flow.usingEmit[Int] { emit =>
              events.add(s"start $x")
              emit(x)
              events.add(s"end $x")
            }
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
              flow.span(myTracing)(s"$prefix-$Level0").tap { case 1 => semaphore.release(); case _ => sleep(100.seconds) }.runToList()
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
            flow.span(myTracing)(s"$prefix-$Level0").tap { case 1 => semaphore.release(); case _ => sleep(100.seconds) }.runToList()
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
          myTracing.byNameSpan(scala.util.boundary.break(1))(s"$prefix-$Level0")
          2

        assert(result == 1)
        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)
        assert(spansOf(Level0).head.getEvents.isEmpty)

      "not pass a boundary break, a control throwable or an interruption to beforeClose" in new Fixture:
        var seen: List[Option[Throwable]] = Nil
        def record(s: SpanWithError) =
          seen = s.errorOpt :: seen
          s.span

        scala.util.boundary:
          myTracing.byNameSpan(scala.util.boundary.break(1))(s"$prefix-$Level0", beforeClose = record)
        intercept[scala.util.control.ControlThrowable]:
          myTracing.byNameSpan(throw new scala.util.control.ControlThrowable {})(s"$prefix-$Level0", beforeClose = record)
        intercept[InterruptedException]:
          myTracing.byNameSpan(throw new InterruptedException())(s"$prefix-$Level0", beforeClose = record)

        assert(seen == List(None, None, None))
        assert(spansOf(Level0).size == 3)
        assert(spansOf(Level0).forall(_.getStatus.getStatusCode == StatusCode.UNSET))

      "treat an interruption during I/O as not a failure" in new Fixture:
        var seen: Option[Throwable] = Some(new Exception("unset"))
        intercept[java.nio.channels.ClosedByInterruptException]:
          myTracing.byNameSpan(throw new java.nio.channels.ClosedByInterruptException())(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s.errorOpt
              s.span
          )

        assert(seen.isEmpty)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "record the closing of a channel by another thread as a failure" in new Fixture:
        intercept[java.nio.channels.AsynchronousCloseException]:
          myTracing.byNameSpan(throw new java.nio.channels.AsynchronousCloseException())(s"$prefix-$Level0")

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "treat an ExecutionException wrapping an interruption as not a failure" in new Fixture:
        var seen: Option[Throwable] = Some(new Exception("unset"))
        intercept[java.util.concurrent.ExecutionException]:
          myTracing.byNameSpan(throw new java.util.concurrent.ExecutionException(new InterruptedException()))(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s.errorOpt
              s.span
          )

        assert(seen.isEmpty)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)

      "treat an ExecutionException wrapping a failure as a failure" in new Fixture:
        intercept[java.util.concurrent.ExecutionException]:
          myTracing.byNameSpan(throw new java.util.concurrent.ExecutionException(new Exception("boom")))(s"$prefix-$Level0")

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "record fatal errors as failures" in new Fixture:
        intercept[LinkageError]:
          myTracing.byNameSpan(throw new LinkageError("broken"))(s"$prefix-$Level0")

        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)

      "pass a failure to beforeClose" in new Fixture:
        var seen: Option[Throwable] = None
        intercept[Exception]:
          myTracing.byNameSpan(throw new Exception("boom"))(
            s"$prefix-$Level0",
            beforeClose = s =>
              seen = s.errorOpt
              s.span
          )

        assert(seen.exists(_.getMessage == "boom"))

      "add the exception of beforeClose as suppressed to an interruption, which is still thrown" in new Fixture:
        val interruption = new InterruptedException()

        val thrown = intercept[InterruptedException]:
          myTracing.byNameSpan(throw interruption)(s"$prefix-$Level0", beforeClose = _ => throw new Exception("from beforeClose"))

        assert(thrown eq interruption)
        assert(thrown.getSuppressed.exists(_.getMessage == "from beforeClose"))
        assert(spansOf(Level0).size == 1)
    }
  }
end OxTelemetryTest
