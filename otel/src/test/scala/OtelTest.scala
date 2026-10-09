import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.BufferCapacity
import ox.either.ok
import ox.flow.Flow
import ox.otel.context.PropagatingVirtualThreadFactory
import ox.telemetry.tracing.OxTracing

import java.util.UUID
import scala.collection.JavaConverters.asScalaBufferConverter


class OtelTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:
  setOxThreadFactory(new PropagatingVirtualThreadFactory)

  class My extends OxApp.WithEitherErrors[String] /* , OtelOxApp.WithOtelSupport */:
    override def run(args: Vector[String])(using Ox, EitherError[String]): ExitCode = ???

    override def handleError(e: String): ExitCode = ???


  val exporter = InMemorySpanExporter.create()

  val tracerProvider =

    SdkTracerProvider.builder()

      .addSpanProcessor(SimpleSpanProcessor.create(exporter))

      .build()

  val openTelemetry =

    OpenTelemetrySdk.builder()

      .setTracerProvider(tracerProvider)

      .build()

  val tracer = openTelemetry.getTracer("test")
  val tracer2 = openTelemetry.tracerBuilder("ftest").build()
  val meter: Meter = openTelemetry.meterBuilder("ftest").build()

  val myTracing: OxTracing = OxTracing.native(tracer)

  import myTracing.*

  "flow.spanned" should "not open span if not runned" in {
    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)


    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))
    assert(thisTestSpans.isEmpty)

  }

  "flow.spanned" should "open one span even if flow has multiple elements" in {
    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)


    val spannedFlow = flow.span(s"$prefix-span")

    spannedFlow.runDrain()

    val spans = exporter.getFinishedSpanItems
    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))

    assert(thisTestSpans.size == 1)

    val span = spans.get(0)

    assert(span.getName == s"$prefix-span")
  }

  "flow.spanned" should "open two spans for two invocations" in {
    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val spanned1 = flow.span(s"$prefix-span") ++ Flow.fromValues(4, 2)

    val spanned2 = spanned1.span(s"$prefix-span2")

    spanned2.runDrain()

    val spans = exporter.getFinishedSpanItems
    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))

    assert(thisTestSpans.size == 2)

    assert(thisTestSpans.head.getName == s"$prefix-span")
    assert(thisTestSpans(1).getName == s"$prefix-span2")
  }


  "flow.spanned" should "open only one span despite parralelized map" in {

    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val spanned = flow.span(s"$prefix-span")

    spanned.mapPar(3)(_ + 1).runDrain()

    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))

    assert(thisTestSpans.size == 1)

    val span = thisTestSpans(0)

    assert(span.getName == s"$prefix-span")
  }


  "flow.spanned" should "properly end span in case of error" in {

    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)


    try {
      flow.tap { case 2 => throw new Exception("boom"); case _ => () }.span(s"$prefix-span", beforeClose = (s, thr) => s.addEvent(thr.toString)).runToList()
    } catch case _ => ()


    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))

    assert(thisTestSpans.size == 1)

    val span = thisTestSpans(0)

    assert(span.getName == s"$prefix-span")
  }
  "flow.spanned" should "properly end span in case of error if mapped after span" in {

    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val spanned = flow.span(s"$prefix-span", beforeClose = (s, thr) => s.addEvent(thr.toString))

    try {
      spanned.map { case 2 => throw new Exception("boom"); case n => n }.runToList()
    } catch case _ => ()


    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))

    assert(thisTestSpans.size == 1)

    val span = thisTestSpans(0)

    assert(span.getName == s"$prefix-span")
  }

  "flow.spanned" should "correctly picks parent span" in {

    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val finalizer = myTracing.spanUnsafe(s"$prefix-parentSpan")

    val spanned = flow.span(s"$prefix-span")

    spanned.runToList(): Unit

    finalizer.close()

    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))

    assert(thisTestSpans.size == 2)

    val (parentSpans, childrenSpans) = thisTestSpans.partitionMap { s => if s.getName.endsWith("parentSpan") then Left(s) else Right(s) }

    assert(parentSpans.size == 1)
    assert(childrenSpans.size == 1)
    val parentSpan = parentSpans.head

    for {
      span <- childrenSpans
    } yield {
      assert(span.getParentSpanId == parentSpan.getSpanId)
    }
  }

  "flow.spanned" should "open several spans if flatMapped" in {

    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val spanned = flow.span(s"$prefix-span")

    flow.flatMap { _ => spanned }.span(s"$prefix-parentSpan").runToList(): Unit

    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))

    assert(thisTestSpans.size == 4)

    val (parentSpans, childrenSpans) = thisTestSpans.partitionMap { s => if s.getName.endsWith("parentSpan") then Left(s) else Right(s) }

    assert(parentSpans.size == 1)
    assert(childrenSpans.size == 3)
    val parentSpan = parentSpans.head

    for {
      span <- childrenSpans
    } yield {
      assert(span.getParentSpanId == parentSpan.getSpanId)
    }
  }

  "flow.spanned" should "properly end span in case of error2" in {

    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val spanned = flow.span(s"$prefix-span")


    spanned.map { case 2 => throw new Exception("boom"); case x => x }.recover(_ => 5).runToList()


    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))

    assert(thisTestSpans.size == 1)

    val span = thisTestSpans(0)

    assert(span.getName == s"$prefix-span")
  }
  "flow.spanned" should "properly end span in case of error2fd" in {

    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val spanned = span(flow)(s"$prefix-span")

    val _ = span(spanned.map { case 2 => throw new Exception("boom"); case x => currentSpanUnsafe().addEvent(x.toString); x }.recover { x => currentSpanUnsafe().addEvent("recovery"); 5 })(s"$prefix-recover").runToList()

    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))

    assert(thisTestSpans.size == 2)

    val recoverySpan = thisTestSpans.find(_.getName.endsWith("recover")).get
    val flowSpan = thisTestSpans.find(_.getName.endsWith("span")).get

    assert(flowSpan.getParentSpanId == recoverySpan.getSpanId)
  }

  "flow.spanned" should "properly end span in case of error2fdfs" in {

    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val spanned = span(flow)(s"$prefix-span")


    val finalinzer = spanUnsafe(s"$prefix-parent")

    supervised {
      (1 to 10).foreach(_ => fork(println(spanned.runToList())))
    }

    finalinzer.close()
    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix)).filterNot(s => s.getSpanId == finalinzer.span.getSpanContext.getSpanId)

    assert(thisTestSpans.size == 10)

    thisTestSpans.foreach {
      s => assert(s.getParentSpanId == finalinzer.span.getSpanContext.getSpanId)
    }
  }

  "flow.spanned" should "properly end span in case of ejknrror2fdfs" in {

    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val spanned = span(flow)(s"$prefix-span")

    val finalinzer = spanUnsafe(s"$prefix-parent")

    supervised {
      (1 to 10).foreach {
        case 1 => fork(spanned.tap(_ => throw new Exception("fds")).runToList())
        case _ => fork(spanned.runToList())
      }
    }

    finalinzer.close()
    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix)).filterNot(s => s.getSpanId == finalinzer.span.getSpanContext.getSpanId)

    assert(thisTestSpans.size == 10)

    thisTestSpans.foreach {
      s => assert(s.getParentSpanId == finalinzer.span.getSpanContext.getSpanId)
    }
  }

  "flow.spanned" should "properly end span in case of ejknrror2fdsfsfdffssfss" in {

    val em = EitherMode[String]
    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val spanned = span(flow)(s"$prefix-level1")

    val finalinzer = spanUnsafe(s"$prefix-level0")

    supervised {
      (1 to 10).map {
        case 1 =>
          forkUser(span(flow)(s"$prefix-1-level1").flatMap {
            el =>
              val innerFlow = supervised {
                flow.span(s"$prefix-level2", beforeClose = _._1.addEvent("boomed"))
              }
              innerFlow
          }.runToList())

        case _ => forkUser(spanned.runToList())
      }
    }

    finalinzer.close()
    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))
    val level1Spans = thisTestSpans.filter(s => s.getName.endsWith("level1"))
    val level2Spans = thisTestSpans.filter(s => s.getName.endsWith("level2"))

    val level1_1 = level1Spans.find(s => s.getName.endsWith("1-level1")).get
    assert(level1Spans.size == 10)
    val level1Span = level1Spans.head
    assert(level2Spans.size == 3)

    level1Spans.foreach {
      s => assert(s.getParentSpanId == finalinzer.span.getSpanContext.getSpanId)
    }

    level2Spans.foreach {
      s => assert(s.getParentSpanId == level1_1.getSpanContext.getSpanId)
    }

  }

  "flow.spanned" should "properly end span in case of ejknrror2fdffssfss" in {

    val em = EitherMode[String]
    val prefix = UUID.randomUUID().toString

    val flow = Flow.fromValues(1, 2, 3)

    val finalinzer = spanUnsafe(s"$prefix-level0")

    val spanned = span(flow)(s"$prefix-level1")

    try
      println:
        supervised {
          (1 to 10).map {
            case 1 => forkUser {
              println:
                (span(flow)(s"$prefix-1-level1").flatMap {
                  el => {
                    supervisedError(em):
                      either:
                        val innerFlow = {
                          em.pure(flow.span(s"$prefix-level2", beforeClose = _._1.addEvent(s"boomed at $el")).map { case 1 => em.pureError("ops").ok(); case x => x })
                        }
                        innerFlow.ok()
                  }.toOption.getOrElse(Flow.empty)
                }.runToList())
            }
            case _ => forkUser(spanned.runToList())
          }
        }
    catch case _ => ()

    finalinzer.close()
    val spans = exporter.getFinishedSpanItems

    val thisTestSpans = spans.asScala.filter(_.getName.startsWith(prefix))
    val level1Spans = thisTestSpans.filter(s => s.getName.endsWith("level1"))
    val level2Spans = thisTestSpans.filter(s => s.getName.endsWith("level2"))
    val level1_1 = level1Spans.find(s => s.getName.endsWith("1-level1")).get

    assert(level1Spans.size == 10)
    val level1Span = level1Spans.head
    assert(level2Spans.size == 1) //because flow.flatMap is evaluated sequentially and exception ends whole flow

    level1Spans.foreach {
      s => assert(s.getParentSpanId == finalinzer.span.getSpanContext.getSpanId)
    }

    level2Spans.foreach {
      s =>
        assert(s.getParentSpanId == level1_1.getSpanContext.getSpanId)
        assert(s.getEvents().get(0).getName.startsWith("boomed"))

    }


  }

end OtelTest
