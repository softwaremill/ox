import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
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

class OxTelemetryTest extends AnyWordSpec with Matchers with BeforeAndAfterAll with Eventually:
  override def beforeAll(): Unit =
    setOxThreadFactory(new PropagatingVirtualThreadFactory)

  val exporter = InMemorySpanExporter.create()

  val tracerProvider =
    SdkTracerProvider
      .builder()
      .addSpanProcessor(SimpleSpanProcessor.create(exporter))
      .build()

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


          val spanned1 = flow.span(myTracing)(s"$prefix-$Level1").map(_+ 100).tap(i => currentSpanUnsafe(myTracing).addEvent(i.toString))

          spanned1.span(myTracing)(s"$prefix-$Level0").runDrain()

          assert(spansOf(Level0).size == 1)
          assert(spansOf(Level1).size == 1)
          assert(testSpans.size == 2)
          assertNesting()
        }
      "open one span per span() call when spans are nested" in new Fixture:
        supervised {
          val spanned1 = (flow.span(myTracing)(s"$prefix-$Level1") ++ Flow.fromValues(4, 2)).tap(i => currentSpanUnsafe(myTracing).addEvent(i.toString))

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

        supervised {
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
            .span(myTracing)(s"$prefix-$Level0", beforeClose = {
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
                    .flatMap { el => {
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
                    .flatMap { el => {
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
                        .flatMap { el => {
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

    "used with downstream stages" should {
      "not make the flow span current in downstream stages" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow.span(myTracing)(s"$prefix-$Level1").tap(_ => myTracing.byNameSpan(())(s"$prefix-$Level1_First")).runToList(): Unit

        parent.close()

        assert(spansOf(Level1).size == 1)
        assert(spansOf(Level1_First).size == 3)
        // spans created downstream are siblings of the flow span, as downstream stages run outside of it
        assertNesting()

      "make the flow span current in upstream stages" in new Fixture:
        val parent = spanUnsafe(myTracing)(s"$prefix-$Level0")

        flow.tap(_ => myTracing.byNameSpan(())(s"$prefix-$Level1_First_Child")).span(myTracing)(s"$prefix-$Level1_First").runToList(): Unit

        parent.close()

        assert(spansOf(Level1_First).size == 1)
        assert(spansOf(Level1_First_Child).size == 3)
        assertNesting()

      "not mark the span as failed when a downstream stage fails" in new Fixture:
        val spanned = flow.span(myTracing)(s"$prefix-$Level0")

        try
          spanned.map { case 2 => eventually(assert(spansOf(Level0).nonEmpty)); throw new Exception("boom"); case n => n }.runToList(): Unit
        catch case _ => ()

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assert(spansOf(Level0).head.getStatus.getStatusCode == StatusCode.UNSET)
        assert(spansOf(Level0).head.getEvents.isEmpty)
        assertNesting()

      "end span when downstream failure is recovered" in new Fixture:
        flow.span(myTracing)(s"$prefix-$Level0").map { case 2 => throw new Exception("boom"); case x => x }.recover(_ => 5).runToList()

        assert(spansOf(Level0).size == 1)
        assert(testSpans.size == 1)
        assertNesting()
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
        assertNesting()
    }
  }
end OxTelemetryTest
