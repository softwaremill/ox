package ox.kafka

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.ExitCode.Success
import ox.otel.context.PropagatingVirtualThreadFactory


class AppTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll//:

//  "flow.spanned" should "not open span if not runned" in new Fixture {
//
//    assert(this.settings.threadFactory.get.isInstanceOf[PropagatingVirtualThreadFactory])
//  }
//  "flow.spanned" should "not open span if not runnefssfd" in new Fixture2 {
//
//    assert(this.settings.threadFactory.get.isInstanceOf[PropagatingVirtualThreadFactory])
//  }

//  class Fixture extends OxApp.WithEitherErrors[String] with OtelOxApp.WithOtelSupport:
//    override def run(args: Vector[String])(using Ox, EitherError[String]): ExitCode = ???
//
//    override def handleError(e: String): ExitCode = ???
//
//
//  class Fixture2 extends OxApp.Simple, OtelOxApp.WithOtelSupport:
//    override protected def settings: OxApp.Settings = super.settings.copy(interruptedExitCode = Success)
//
//    override def run(using Ox): Unit = ???

end AppTest
