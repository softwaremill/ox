package ox.plugin.test

import dotty.tools.dotc.Compiler
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.reporting.{Diagnostic, TestingReporter}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Properties

class RequireIOTest extends AnyFunSpec with Matchers:
  private def compilerContext(): Context =
    val base = new ContextBase {}
    val compilerClasspath = Properties.propOrEmpty("scala-compiler-classpath") ++ s":${Properties.propOrEmpty("scala-compiler-plugin")}"
    val context = base.initialCtx.fresh
    context.setSetting(context.settings.classpath, compilerClasspath)
    context.setSetting(context.settings.plugin, List(Properties.propOrEmpty("scala-compiler-plugin")))
    context.setReporter(new TestingReporter)
    base.initialize()(using context)
    context

  private def compile(source: String): Seq[Diagnostic.Error] =
    given Context = compilerContext()
    val c = new Compiler
    val run = c.newRun
    run.compileFromStrings(List(source))
    run.runContext.reporter.allErrors

  private def checkCompiles(source: String): Unit =
    val _ = compile(source) shouldBe empty

  private def checkDoesNotCompile(source: String): String =
    val result = compile(source)
    result should not be empty
    result.map(_.msg).mkString("\n")

  val expectedError = "The java.io.InputStream.read method throws an IOException"

  describe("should not compile") {
    it("direct invocations") {
      checkDoesNotCompile("""
          |import java.io.InputStream
          |
          |object Test2:
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
          |object Test5 {
          |  def test(): Unit = {
          |    IO.unsafe {
          |    }
          |    val is: InputStream = ???
          |    is.read()
          |  }
          |}""".stripMargin) should include(expectedError)
    }

    it("capavility in another method") {
      checkDoesNotCompile("""
          |import java.io.InputStream
          |import ox.IO
          |
          |object Test5 {
          |  def test()(using IO): Unit = {}
          |  def test2(): Unit = {
          |    val is: InputStream = ???
          |    is.read()
          |  }
          |  def test3()(using IO): Unit = {}
          |}""".stripMargin) should include(expectedError)
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

    it("using a method throwing another exception") {
      checkCompiles("""
          |import java.util.concurrent.Semaphore
          |
          |object Test3 {
          |  def test(): Unit = {
          |    val s: Semaphore = ???
          |    s.acquire()
          |  }
          |}""".stripMargin)
    }
  }
