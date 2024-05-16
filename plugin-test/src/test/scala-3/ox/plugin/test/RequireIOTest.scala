package ox.plugin.test

import dotty.tools.dotc.Compiler
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.reporting.{Diagnostic, TestingReporter}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Properties

class RequireIOTest extends AnyFunSpec with Matchers:
  private def compilerContext(pluginOptions: List[String]): Context =
    val base = new ContextBase {}
    val compilerClasspath = Properties.propOrEmpty("scala-compiler-classpath") ++ s":${Properties.propOrEmpty("scala-compiler-plugin")}"
    val context = base.initialCtx.fresh
    context.setSetting(context.settings.classpath, compilerClasspath)
    context.setSetting(context.settings.plugin, List(Properties.propOrEmpty("scala-compiler-plugin")))
    context.setSetting(context.settings.pluginOptions, pluginOptions)
    context.setReporter(new TestingReporter)
    base.initialize()(using context)
    context

  private def compile(source: String, pluginOptions: List[String]): Seq[Diagnostic.Error] =
    given Context = compilerContext(pluginOptions)
    val c = new Compiler
    val run = c.newRun
    run.compileFromStrings(List(source))
    run.runContext.reporter.allErrors

  private def checkCompiles(source: String, pluginOptions: List[String] = Nil): Unit =
    val _ = compile(source, pluginOptions) shouldBe empty

  private def checkDoesNotCompile(source: String, pluginOptions: List[String] = Nil): String =
    val result = compile(source, pluginOptions)
    result should not be empty
    result.map(_.msg).mkString("\n")

  val expectedError = "The `java.io.InputStream.read` method throws an `java.io.IOException`"

  describe("should not compile") {
    it("direct invocations") {
      checkDoesNotCompile("""
          |import java.io.InputStream
          |
          |object Test:
          |  def test(): Unit =
          |    val is: InputStream = ???
          |    is.read()
          |""".stripMargin) should include(expectedError)
    }

    it("using IO.unsafe outside of the invocation") {
      checkDoesNotCompile("""
          |import java.io.InputStream
          |import ox.IO
          |
          |object Test {
          |  def test(): Unit = {
          |    IO.unsafe {
          |    }
          |    val is: InputStream = ???
          |    is.read()
          |  }
          |}""".stripMargin) should include(expectedError)
    }

    it("capability in another method") {
      checkDoesNotCompile("""
          |import java.io.InputStream
          |import ox.IO
          |
          |object Test {
          |  def test()(using IO): Unit = {}
          |  def test2(): Unit = {
          |    val is: InputStream = ???
          |    is.read()
          |  }
          |  def test3()(using IO): Unit = {}
          |}""".stripMargin) should include(expectedError)
    }

    it("another exception, when configured to require IO") {
      checkDoesNotCompile(
        """
          |object Test {
          |  def test(): Unit = {
          |    val f: java.util.concurrent.Future[String] = ???
          |    f.get()
          |  }
          |}""".stripMargin,
        List("requireIO:java.util.concurrent.ExecutionException")
      ) should include("The `java.util.concurrent.Future.get` method throws an `java.util.concurrent.ExecutionException`")
    }
  }

  describe("should compile") {
    it("direct invocations with the capability available") {
      checkCompiles("""
          |import java.io.InputStream
          |import ox.IO
          |
          |object Test2:
          |  def test()(using IO): Unit =
          |    val is: InputStream = ???
          |    is.read()
          |""".stripMargin)
    }

    it("using IO.unsafe") {
      checkCompiles("""
          |import java.io.InputStream
          |import ox.IO
          |
          |object Test5 {
          |  def test(): Unit = {
          |    IO.unsafe {
          |      val is: InputStream = ???
          |      is.read()
          |    }
          |  }
          |}""".stripMargin)
    }

    it("using external capability") {
      checkCompiles("""
          |import java.io.InputStream
          |import ox.IO
          |
          |object Test3 {
          |  given IO = new IO {}
          |  def test(): Unit = {
          |    val is: InputStream = ???
          |    is.read()
          |  }
          |}""".stripMargin)
    }

    it("another exception, when not configured to require IO") {
      checkCompiles("""
          |object Test {
          |  def test(): Unit = {
          |    val f: java.util.concurrent.Future[String] = ???
          |    f.get()
          |  }
          |}""".stripMargin)
    }
  }
