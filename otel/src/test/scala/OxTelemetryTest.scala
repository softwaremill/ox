import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.{BatchSpanProcessor, SimpleSpanProcessor}
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import ox.*
import ox.channels.{BufferCapacity, Channel, Sink, Source}
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
    sys.env.get("OTEL_EXPORTER_OTLP_ENDPOINT").foreach: endpoint =>
      builder.addSpanProcessor(BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build()).build())
    builder.build()

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
        flow.spanned(myTracing)(s"$prefix-$Level0"): Unit
        assert(testSpans.isEmpty)
        assertNesting()

      "open a single span for a multi-element flow" in new Fixture:
        flow.spanned(myTracing)(s"$prefix-$Level0").runDrain()

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assertNesting()

      "handle buffer one span per span() call when spans are nested" in new Fixture:
        supervised {


          val spanned1 = flow.spanned(myTracing)(s"$prefix-$Level1").map(_+ 100).tap(i => currentSpanUnsafe(myTracing).addEvent(i.toString))

          spanned1.spanned(myTracing)(s"$prefix-$Level0").runDrain()

          assert(spansOf(Level0).size == 1)
          assert(spansOf(Level1).size == 1)
          assert(testSpans.size == 2)
          assertNesting()
        }
      "open one span per span() call when spans are nested" in new Fixture:
        supervised {
          val spanned1 = (flow.spanned(myTracing)(s"$prefix-$Level1") ++ Flow.fromValues(4, 2)).tap(i => currentSpanUnsafe(myTracing).addEvent(i.toString))

          spanned1.spanned(myTracing)(s"$prefix-$Level0").runDrain()

          assert(spansOf(Level0).size == 1)
          assert(spansOf(Level1).size == 1)
          assert(testSpans.size == 2)
          assertNesting()
        }

      "open a single span when downstream uses mapPar" in new Fixture:
        flow.spanned(myTracing)(s"$prefix-$Level0").mapPar(3)(_ + 1).runDrain()

        assert(testSpans.size == 1)

        val only = spansOf(Level0).head
        assert(only.getStatus.getStatusCode == StatusCode.UNSET)
        assert(only.getEvents.isEmpty)
        assertNesting()
    }

    "used under parent spans" should {
      "use the current span as parent" in new Fixture:
        val parent = myTracing.spanUnsafe(s"$prefix-$Level0")

        flow.spanned(myTracing)(s"$prefix-$Level1").runToList(): Unit

        parent.close()

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 1)
        assertNesting()

      "open one child span per element when flatMapped under a parent span" in new Fixture:
        val spanned = flow.spanned(myTracing)(s"$prefix-$Level1")

        flow.flatMap { _ => spanned }.spanned(myTracing)(s"$prefix-$Level0").runToList(): Unit

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 3)
        assertNesting()

      "parent spans of concurrently run flows under the enclosing span" in new Fixture:
        val spanned = flow.spanned(myTracing)(s"$prefix-$Level1")

        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        supervised {
          (1 to 10).foreach(_ => fork(spanned.runToList()))
        }

        parent.close()

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 10)
        assertNesting()

      "nest spans across forks and inner supervised scopes" in new Fixture:
        val spanned = flow.spanned(myTracing)(s"$prefix-$Level1")

        supervised {
          (1 to 10).map {
            case 1 =>
              forkUser(
                flow
                  .flatMap { el =>
                    val innerFlow = supervised {
                      flow.spanned(myTracing)(s"$prefix-$Level1_First_Child", beforeClose = _._1.addEvent("boomed"))
                    }
                    innerFlow
                  }
                  .spanned(myTracing)(s"$prefix-$Level1_First")
                  .runToList()
              )

            case _ => forkUser(spanned.runToList())
          }
        }.byNameSpan(myTracing)(s"$prefix-$Level0")

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
            .spanned(myTracing)(s"$prefix-$Level0", beforeClose = {
              case (s, thr) => s.addEvent(thr.toString)
            })
            .runToList()
        catch case _ => ()

        assert(testSpans.size == 1)

        val failed = spansOf(Level0).head
        assert(failed.getStatus.getStatusCode == StatusCode.ERROR)
        assert(failed.getStatus.getDescription == "boom")
        assert(failed.getEvents.asScala.exists(_.getName == "exception"))
        assertNesting()

      "keep the flow span as a child of the recovery span when failure is recovered" in new Fixture:
        val spanned = flow.spanned(myTracing)(s"$prefix-$Level1")

        spanned
          .map { case 2 => throw new Exception("boom"); case x => x }
          .recover(_ => 5)
          .spanned(myTracing)(s"$prefix-$Level0")
          .runToList(): Unit

        assert(spansOf(Level0).size == 1)
        assert(spansOf(Level1).size == 1)
        assertNesting()

      "end spans of concurrent flows when one of them fails" in new Fixture:
        val spanned = flow.spanned(myTracing)(s"$prefix-$Level1")

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

        val spanned = flow.spanned(myTracing)(s"$prefix-$Level1")

        try
          supervised {
            (1 to 10).map {
              case 1 =>
                forkUser {
                  flow
                    .flatMap { el => {
                      val inner = supervisedError(em):
                        either:
                          val innerFlow =
                            em.pure(
                              flow.spanned(myTracing)(s"$prefix-$Level1_First_Child", beforeClose = _._1.addEvent(s"boomed at $el")).map {
                                case 1 => em.pureError("ops").ok();
                                case x => x
                              }
                            )
                          innerFlow.ok()
                      inner
                    }.toOption.get
                    }
                    .spanned(myTracing)(s"$prefix-$Level1_First")
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

        val spanned = flow.spanned(myTracing)(s"$prefix-$Level1")

        try
          supervised {
            (1 to 10).map {
              case 1 =>
                forkUser {
                  flow
                    .flatMap { el => {
                      val inner = supervisedError(em):
                        either:
                          val innerFlow =
                            em.pure(
                              flow.spanned(myTracing)(s"$prefix-$Level1_First_Child", beforeClose = _._1.addEvent(s"boomed at $el")).map {
                                case 1 => em.pureError("ops").ok();
                                case x => x
                              }
                            )
                          innerFlow.ok()
                      inner
                    }.toOption.getOrElse(Flow.empty)
                    }
                    .spanned(myTracing)(s"$prefix-$Level1_First")
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

        val spanned = flow.spanned(myTracing)(s"$prefix-$Level1")

        try
          supervisedError(em) {
            either:
              em.pure:
                (1 to 10).map {
                  case 1 =>
                    forkUser {
                      flow
                        .flatMap { el => {
                          supervisedError(em):
                            val innerFlow =
                              em.pure(
                                flow.spanned(myTracing)(s"$prefix-$Level1_First_Child", beforeClose = _._1.addEvent(s"boomed at $el")).map {
                                  case 1 => em.pureError("ops").ok();
                                  case x => x
                                }
                              )
                            innerFlow
                        }.toOption.get
                        }
                        .spanned(myTracing)(s"$prefix-$Level1_First")
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

    "used with downstream stages" should {
      "make the flow span current in downstream stages" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow.spanned(myTracing)(s"$prefix-$Level1_First").tap(_ => myTracing.byNameSpan(())(s"$prefix-$Level1_First_Child")).runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 1)
        // downstream stages are called from within the span, so spans created there are its children
        assert(spansOf(Level1_First_Child).size == 3)
        assertNesting()

      "make the flow span current in upstream stages" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow.tap(_ => myTracing.byNameSpan(())(s"$prefix-$Level1_First_Child")).spanned(myTracing)(s"$prefix-$Level1_First").runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 1)
        assert(spansOf(Level1_First_Child).size == 3)
        assertNesting()

      "end span when a downstream stage fails" in new Fixture:
        try flow.spanned(myTracing)(s"$prefix-$Level0").map { case 2 => throw new Exception("boom"); case n => n }.runToList(): Unit
        catch case _ => ()

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.ERROR)
        assertNesting()

      "end span when downstream failure is recovered" in new Fixture:
        flow.spanned(myTracing)(s"$prefix-$Level0").map { case 2 => throw new Exception("boom"); case x => x }.recover(_ => 5).runToList()

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assertNesting()
    }

    "spanEachDownstream" should {
      "open a span per element, around the stages placed after it" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .spanEachDownstream(myTracing)(s"$prefix-$Level1_First")
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_First_Child"))
          .runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 3)
        assert(spansOf(Level1_First_Child).size == 3)
        assertNesting()

      "not cover the stages placed before it" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow.map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1")).spanEachDownstream(myTracing)(s"$prefix-$Level1_First").runToList(): Unit

        parent.close()

        // spans created upstream are not children of the per-element spans
        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1_First).size == 3)
        assertNesting()

      "open a span per element, with the stage and fan-out downstream spans as its children" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .map(_ + 1)
          .spanEachDownstream(myTracing)(s"$prefix-$Level1_First")
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage"))
          .flatMap(x => Flow.fromValues(1 to x*))
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Downstream"))
          .runToList(): Unit

        parent.close()

        val elementSpans = spansOf(Level1_First)
        assert(elementSpans.size == 3)

        // one stage span per element
        assert(spansOf(Level1_Stage).size == 3)
        // elements 1, 2, 3 are mapped to 2, 3, 4, and fanned out to that many outputs, each of which is passed to the last map
        assert(spansOf(Level1_Downstream).size == 9)

        val childrenPerElement = (spansOf(Level1_Stage) ++ spansOf(Level1_Downstream)).groupBy(_.getParentSpanId)
        assert(childrenPerElement.keySet == elementSpans.map(_.getSpanId).toSet)
        assert(childrenPerElement.values.map(_.size).toList.sorted == List(3, 4, 5))
    }

    "used with async stages downstream" should {
      "make a span after buffer a sibling of the per-element spans" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .spanEachDownstream(myTracing)(s"$prefix-$Level1")
          .buffer()
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage"))
          .runToList(): Unit

        parent.close()

        val level0Id = spansOf(Level0).head.getSpanId
        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1_Stage).size == 3)
        // buffer's consumer doesn't run within the per-element spans, it keeps the context of where the flow is run
        assert(spansOf(Level1_Stage).forall(_.getParentSpanId == level0Id))
        assert(spansOf(Level1).forall(_.getParentSpanId == level0Id))

      "keep the stages between the span and buffer as children, and the ones after it as siblings" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .spanEachDownstream(myTracing)(s"$prefix-$Level1")
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage"))
          .buffer()
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Downstream"))
          .runToList(): Unit

        parent.close()

        val level0Id = spansOf(Level0).head.getSpanId
        val elementIds = spansOf(Level1).map(_.getSpanId).toSet
        assert(spansOf(Level1_Stage).size == 3)
        assert(spansOf(Level1_Downstream).size == 3)
        assert(spansOf(Level1_Stage).forall(s => elementIds.contains(s.getParentSpanId)))
        assert(spansOf(Level1_Downstream).forall(_.getParentSpanId == level0Id))

      "make a span after buffer a sibling of the whole-run span" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .spanned(myTracing)(s"$prefix-$Level1")
          .buffer()
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage"))
          .runToList(): Unit

        parent.close()

        val level0Id = spansOf(Level0).head.getSpanId
        assert(spansOf(Level1).size == 1)
        assert(spansOf(Level1_Stage).size == 3)
        assert(spansOf(Level1_Stage).forall(_.getParentSpanId == level0Id))

      "make a span in mapPar a child of the per-element spans" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .spanEachDownstream(myTracing)(s"$prefix-$Level1")
          .mapPar(2)(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage"))
          .runToList(): Unit

        parent.close()

        val elementIds = spansOf(Level1).map(_.getSpanId).toSet
        assert(spansOf(Level1_Stage).size == 3)
        // mapPar forks the function from within the emit, so the forks inherit the per-element span
        assert(spansOf(Level1_Stage).forall(s => elementIds.contains(s.getParentSpanId)))

      "not end the span at a buffer placed inside a later flatMap" in new Fixture:
        Flow
          .fromValues(1, 2, 3)
          .flatMap(x => Flow.fromValues(x).tap(_ => sleep(100.millis)).spanned(myTracing)(s"$prefix-$Level1"))
          .tap(_ => sleep(100.millis))
          .flatMap(x => Flow.fromValues(x).buffer())
          .tap(_ => sleep(100.millis))
          .runToList(): Unit

        // the buffer is inside the flow returned by flatMap, its consumer is drained from within the upstream emit,
        // so the span still covers both taps placed after it
        assert(spansOf(Level1).size == 3)
        spansOf(Level1).foreach(s => assert((s.getEndEpochNanos - s.getStartEpochNanos) / 1000000 >= 300))

      "end the span at a buffer placed directly in the flow" in new Fixture:
        Flow
          .fromValues(1, 2, 3)
          .flatMap(x => Flow.fromValues(x).tap(_ => sleep(100.millis)).spanned(myTracing)(s"$prefix-$Level1"))
          .buffer()
          .tap(_ => sleep(100.millis))
          .runToList(): Unit

        assert(spansOf(Level1).size == 3)
        spansOf(Level1).foreach(s => assert((s.getEndEpochNanos - s.getStartEpochNanos) / 1000000 < 200))
      // one element, with sleeps far apart: a = first generator, b = second generator, c = stage placed after the whole comprehension
      val a = 200.millis
      val b = 600.millis
      val c = 1200.millis
      val (aMs, bMs, cMs) = (a.toMillis, b.toMillis, c.toMillis)

      "keep the outer span over a recoverWith placed inside the later generator" in new Fixture:
        val filesToDownload = Seq(1)
        def download(file: Int): Int =
          sleep(a)
          file
        def readLines(file: Int): Flow[String] = Flow.fromValues(s"line of $file").tap(_ => sleep(b))
        def streamLinesFlow(file: Int) = readLines(file).spanned(myTracing)(s"$prefix-$Level1_First")
        (for
          file <- Flow.fromIterable(filesToDownload).map(download).spanned(myTracing)(s"$prefix-$Level1")
          line <- streamLinesFlow(file)
        yield line).tap(_ => sleep(c)).runToList(): Unit

        val outer = spansOf(Level1).head
        val inner = spansOf(Level1_First).head
        // recoverWith drains its channel from within the outer emit, so the outer span still covers the last sleep
        assertAbout(durMs(outer), aMs + bMs + cMs)
        // the inner span is inside the fork of recoverWith, so it ends at the hand-over
        assertAbout(durMs(inner), bMs)
        assert(inner.getParentSpanId == outer.getSpanId)

      "open a span per file with spanEachDownstream, keeping the following stages inside it" in new Fixture:
        val filesToDownload = Seq(1, 2)
        def download(file: Int): Int =
          sleep(a)
          file
        def readLines(file: Int): Flow[String] = Flow.fromValues(s"line of $file").tap(_ => sleep(b))
        def streamLinesFlow(file: Int) = readLines(file).spanned(myTracing)(s"$prefix-$Level1_First")
        (for
          file <- Flow.fromIterable(filesToDownload).map(download).spanEachDownstream(myTracing)(s"$prefix-$Level1")
          line <- streamLinesFlow(file).recoverWith(_ => streamLinesFlow(file))
        yield line).tap(_ => sleep(c)).runToList(): Unit

        val perFile = spansOf(Level1).sortBy(_.getStartEpochNanos)
        val lines = spansOf(Level1_First)
        assert(perFile.size == 2 && lines.size == 2)
        // download is upstream of the span, and recoverWith ends only the lines span at the hand-over, so a file's span is b + c
        perFile.foreach(s => assertAbout(durMs(s), bMs + cMs))
        lines.foreach(s => assertAbout(durMs(s), bMs))
        // each lines span belongs to a different file span
        assert(lines.map(_.getParentSpanId).toSet == perFile.map(_.getSpanId).toSet)
        // the second file is downloaded only after the first one is fully processed
        assertAbout(startOffsetMs(perFile(0), perFile(1)), bMs + cMs + aMs)

      "open a span per file with mapSpanned, covering only the download" in new Fixture:
        val filesToDownload = Seq(1, 2)
        def download(file: Int): Int =
          sleep(a)
          file
        def readLines(file: Int): Flow[String] = Flow.fromValues(s"line of $file").tap(_ => sleep(b))
        def streamLinesFlow(file: Int) = readLines(file).spanned(myTracing)(s"$prefix-$Level1_First")
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

      "count the nested generators and the following stages in a flat for comprehension with spanned" in new Fixture:
        (for
          x <- Flow.fromValues(1)
          y <- Flow.fromValues(x).tap(_ => sleep(a)).spanned(myTracing)(s"$prefix-$Level1")
          z <- Flow.fromValues(y).tap(_ => sleep(b)).spanned(myTracing)(s"$prefix-$Level1_First")
        yield z).tap(_ => sleep(c)).runToList(): Unit

        val y = spansOf(Level1).head
        val z = spansOf(Level1_First).head
        assert(z.getParentSpanId == y.getSpanId)
        assertAbout(durMs(y), aMs + bMs + cMs)
        assertAbout(durMs(z), bMs + cMs)
        assertAbout(startOffsetMs(y, z), aMs)

      "count only its own flow in a flat for comprehension with spannedAsync" in new Fixture:
        (for
          x <- Flow.fromValues(1,2,3)
          y <- myTracing.spannedAsync(Flow.fromValues(x).tap(_ => sleep(a)))(s"$prefix-$Level1")
          z <- myTracing.spannedAsync(Flow.fromValues(y).tap(_ => sleep(b)))(s"$prefix-$Level1_First")
        yield z)
          .tap(_ => sleep(c)).runToList(): Unit

        val y = spansOf(Level1).head
        val z = spansOf(Level1_First).head
        assert(z.getParentSpanId != y.getSpanId)
        assertAbout(durMs(y), aMs)
        assertAbout(durMs(z), bMs)
        assertAbout(startOffsetMs(y, z), aMs)

      "end the first span at a buffer placed after it in a flat for comprehension with spanned" in new Fixture:
        (for
          x <- Flow.fromValues(1)
          y <- Flow.fromValues(x).tap(_ => sleep(a)).spanned(myTracing)(s"$prefix-$Level1").buffer()
          z <- Flow.fromValues(y).tap(_ => sleep(b)).spanned(myTracing)(s"$prefix-$Level1_First")
        yield z).tap(_ => sleep(c)).runToList(): Unit

        val y = spansOf(Level1).head
        val z = spansOf(Level1_First).head
        assert(z.getParentSpanId != y.getSpanId)
        assertAbout(durMs(y), aMs)
        assertAbout(durMs(z), bMs + cMs)
        assertAbout(startOffsetMs(y, z), aMs)

      "nest the durations of a spannedAsync in a flatMapSpanned" in new Fixture:
        Flow
          .fromValues(1)
          .flatMapSpanned(myTracing)(s"$prefix-$Level1")(x =>
            for
              y <- Flow.fromValues(x).tap(_ => sleep(a))
              z <- myTracing.spannedAsync(Flow.fromValues(y).tap(_ => sleep(b)))(s"$prefix-$Level1_First")
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

      "not end the span at alsoTo, as it sends to the sink synchronously" in new Fixture:
        supervised:
          val sink = Channel.rendezvous[Int]
          fork:
            for _ <- 1 to 3 do
              sleep(200.millis)
              sink.receive(): Unit
          Flow
            .fromValues(1, 2, 3)
            .flatMap(x => Flow.fromValues(x).spanned(myTracing)(s"$prefix-$Level1"))
            .alsoTo(sink)
            .runToList(): Unit

        // no sleeps in the flow, yet each span lasts as long as the send to the slow sink blocks
        assert(spansOf(Level1).size == 3)
        spansOf(Level1).foreach(s => assert((s.getEndEpochNanos - s.getStartEpochNanos) / 1000000 >= 150))
    }

    "spannedIsolated" should {
      def durationMs(span: io.opentelemetry.sdk.trace.data.SpanData) = (span.getEndEpochNanos - span.getStartEpochNanos) / 1000000

      "make the spans created in upstream stages children, and in downstream stages siblings" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        myTracing
          .spannedIsolated(flow.tap(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_First")))(s"$prefix-$Level1")
          .tap(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage"))
          .runToList(): Unit

        parent.close()

        val level0Id = spansOf(Level0).head.getSpanId
        val level1Id = spansOf(Level1).head.getSpanId
        assert(spansOf(Level1).size == 1)
        assert(spansOf(Level1).head.getParentSpanId == level0Id)
        assert(spansOf(Level1_First).size == 3 && spansOf(Level1_First).forall(_.getParentSpanId == level1Id))
        assert(spansOf(Level1_Stage).size == 3 && spansOf(Level1_Stage).forall(_.getParentSpanId == level0Id))

      "keep the spans created in stages before and after a buffer placed before it as children" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        myTracing
          .spannedIsolated(
            flow
              .tap(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_First"))
              .buffer()
              .tap(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage"))
          )(s"$prefix-$Level1")
          .runToList(): Unit

        parent.close()

        val level1Id = spansOf(Level1).head.getSpanId
        // both the producer (before the buffer) and the consumer (after it) run within the span
        assert(spansOf(Level1_First).size == 3 && spansOf(Level1_First).forall(_.getParentSpanId == level1Id))
        assert(spansOf(Level1_Stage).size == 3 && spansOf(Level1_Stage).forall(_.getParentSpanId == level1Id))

      "make the spans created in stages after a buffer placed after it siblings" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        myTracing
          .spannedIsolated(flow.tap(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_First")))(s"$prefix-$Level1")
          .buffer()
          .tap(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage"))
          .runToList(): Unit

        parent.close()

        val level0Id = spansOf(Level0).head.getSpanId
        val level1Id = spansOf(Level1).head.getSpanId
        assert(spansOf(Level1_First).forall(_.getParentSpanId == level1Id))
        assert(spansOf(Level1_Stage).size == 3 && spansOf(Level1_Stage).forall(_.getParentSpanId == level0Id))

      "count the time of synchronous downstream stages" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        myTracing.spannedIsolated(flow)(s"$prefix-$Level1").tap(_ => sleep(200.millis)).runToList(): Unit

        parent.close()

        assert(durationMs(spansOf(Level1).head) >= 500)

      "not count the time of downstream stages after a buffer" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        myTracing.spannedIsolated(flow)(s"$prefix-$Level1").buffer().tap(_ => sleep(200.millis)).runToList(): Unit

        parent.close()

        // upstream is done quickly, while the downstream takes 3 * 200ms
        assert(durationMs(spansOf(Level1).head) < 300)

      "not mark the span as failed when a downstream stage fails" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        try myTracing.spannedIsolated(flow)(s"$prefix-$Level1").map { case 2 => throw new Exception("boom"); case x => x }.runToList(): Unit
        catch case _ => ()

        parent.close()

        assert(spansOf(Level1).size == 1)
        assert(spansOf(Level1).head.getStatus.getStatusCode == StatusCode.UNSET)
        assert(spansOf(Level1).head.getEvents.isEmpty)

      "mark the span as failed when an upstream stage fails" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        try myTracing.spannedIsolated(flow.map { case 2 => throw new Exception("boom"); case x => x })(s"$prefix-$Level1").runToList(): Unit
        catch case _ => ()

        parent.close()

        assert(spansOf(Level1).head.getStatus.getStatusCode == StatusCode.ERROR)
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
        try flow.flatMapSpanned(myTracing)(s"$prefix-$Level0")(x => Flow.fromValues(x)).map { case 2 => throw new Exception("boom"); case x => x }.runToList(): Unit
        catch case _ => ()

        assert(spansOf(Level0).nonEmpty)
        assert(spansOf(Level0).forall(_.getStatus.getStatusCode == StatusCode.UNSET))
        assert(spansOf(Level0).forall(_.getEvents.isEmpty))

      "mark only the span of the inner flow that fails" in new Fixture:
        try
          flow
            .flatMapSpanned(myTracing)(s"$prefix-$Level0")(x => Flow.fromValues(x).map { case 2 => throw new Exception("boom"); case y => y })
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

    "spanEachStages" should {
      "open a span per element over the stages, with the spans of the stages as children and the other ones as siblings" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow
          .tap(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_First"))
          .spanEachStages(myTracing)(s"$prefix-$Level1")(
            _.map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Stage")).flatMap(x => Flow.fromValues(x, x * 10))
          )
          .map(x => myTracing.byNameSpan(x)(s"$prefix-$Level1_Downstream"))
          .runToList(): Unit

        parent.close()

        val level0Id = spansOf(Level0).head.getSpanId
        val elementIds = spansOf(Level1).map(_.getSpanId).toSet
        assert(spansOf(Level1).size == 3)
        assert(spansOf(Level1).forall(_.getParentSpanId == level0Id))
        // created before the stages, so outside of the element spans
        assert(spansOf(Level1_First).size == 3 && spansOf(Level1_First).forall(_.getParentSpanId == level0Id))
        // one per element, in the stages
        assert(spansOf(Level1_Stage).size == 3 && spansOf(Level1_Stage).forall(s => elementIds.contains(s.getParentSpanId)))
        // each element is extrapolated to 2 elements, which go through the stage after
        assert(spansOf(Level1_Downstream).size == 6 && spansOf(Level1_Downstream).forall(_.getParentSpanId == level0Id))

      "work with stages that conflate the elements" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        val result = Flow.fromValues(1, 2, 3, 4).spanEachStages(myTracing)(s"$prefix-$Level1")(_.grouped(2)).runToList()

        parent.close()

        assert(result == List(List(1, 2), List(3, 4)))
        assert(spansOf(Level1).size == 4)
        assert(spansOf(Level1).forall(_.getParentSpanId == spansOf(Level0).head.getSpanId))

      "work with infinite flows" in new Fixture:
        val result = Flow.iterate(1)(_ + 1).spanEachStages(myTracing)(s"$prefix-$Level0")(_.map(_ * 2)).take(3).runToList()

        assert(result == List(2, 4, 6))
        // the spans of the elements that were being processed when the flow was stopped are not marked as failed
        assert(spansOf(Level0).size >= 3)
        assert(spansOf(Level0).forall(_.getStatus.getStatusCode == StatusCode.UNSET))
    }

    "nested whole-run spans over a concat" should {
      def durationMs(span: io.opentelemetry.sdk.trace.data.SpanData) = (span.getEndEpochNanos - span.getStartEpochNanos) / 1000000

      "cover the downstream stages and the flows run before them" in new Fixture:
        // 3 elements, each taking 100ms to produce
        val slowFlow = Flow.fromValues(1, 2, 3).tap(_ => sleep(100.millis))

        val start = System.nanoTime()
        slowFlow
          .spanned(myTracing)(s"$prefix-$Level1")
          .concat(slowFlow)
          .spanned(myTracing)(s"$prefix-$Level0")
          .tap(_ => sleep(100.millis))
          .runToList(): Unit
        val totalMs = (System.nanoTime() - start) / 1000000

        val inner = spansOf(Level1).head
        val outer = spansOf(Level0).head

        // 6 elements, each taking 100ms to produce and 100ms to be processed by the last stage
        assert(totalMs >= 1150 && totalMs < 1800)
        // the outer span covers the whole concat
        assert(durationMs(outer) >= 1150 && durationMs(outer) < 1800)
        // the inner span covers the first flow and the last stage's processing of its 3 elements
        assert(durationMs(inner) >= 550 && durationMs(inner) < 900)
        // it was opened inside the outer span
        assert(inner.getParentSpanId == outer.getSpanId)
        assert(inner.getStartEpochNanos >= outer.getStartEpochNanos && inner.getEndEpochNanos <= outer.getEndEpochNanos)
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
              flow.spanned(myTracing)(s"$prefix-$Level0").tap { case 1 => semaphore.release(); case _ => sleep(100.seconds) }.runToList()
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
            flow.spanned(myTracing)(s"$prefix-$Level0").tap { case 1 => semaphore.release(); case _ => sleep(100.seconds) }.runToList()
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
    }
  }
end OxTelemetryTest
