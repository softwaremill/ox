package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.ExitCode.*

import java.io.{PrintWriter, StringWriter}
import java.util.concurrent.CountDownLatch
import scala.util.boundary.*
import scala.concurrent.duration.*

class OxAppTest extends AnyFlatSpec with Matchers:

  "OxApp" should "work in happy case" in {
    var ec = Int.MinValue

    object Main1 extends OxApp:
      override def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      def run(args: Vector[String])(using Ox): ExitCode = Success

    Main1.main(Array.empty)

    ec shouldEqual 0
  }

  "OxApp" should "work in interrupted case" in {
    var ec = Int.MinValue
    val shutdownLatch = CountDownLatch(1)

    object Main2 extends OxApp:
      override private[ox] def mountShutdownHook(thread: Thread): Unit =
        val damoclesThread = Thread(() => {
          shutdownLatch.await()
          thread.start()
          thread.join()
        })

        damoclesThread.start()

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      def run(args: Vector[String])(using Ox): ExitCode =
        forever: // will never finish
          sleep(10.millis)

        Success

    supervised:
      fork(Main2.main(Array.empty))
      sleep(10.millis)
      shutdownLatch.countDown()

    ec shouldEqual 0
  }

  "OxApp" should "work in failed case" in {
    var ec = Int.MinValue
    var stackTrace = ""

    object Main3 extends OxApp:
      override def run(args: Vector[String])(using Ox): ExitCode =
        Failure(23)

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

    Main3.main(Array.empty)

    ec shouldEqual 23

    ec = Int.MinValue

    object Main4 extends OxApp:
      override def run(args: Vector[String])(using Ox): ExitCode =
        throw Exception("oh no")

      override private[ox] def printStackTrace(t: Throwable): Unit =
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        stackTrace = sw.toString

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

    Main4.main(Array.empty)

    ec shouldEqual 1
    assert(stackTrace.contains("oh no"))
  }

  "OxApp.Simple" should "work in happy case" in {
    var ec = Int.MinValue

    object Main5 extends OxApp.Simple:
      override def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override def run(using Ox): Unit = ()

    Main5.main(Array.empty)

    ec shouldEqual 0
  }

  "OxApp.Simple" should "work in interrupted case" in {
    var ec = Int.MinValue
    val shutdownLatch = CountDownLatch(1)

    object Main6 extends OxApp.Simple:
      override private[ox] def mountShutdownHook(thread: Thread): Unit =
        val damoclesThread = Thread(() => {
          shutdownLatch.await()
          thread.start()
          thread.join()
        })

        damoclesThread.start()

      override def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override def run(using Ox): Unit =
        forever:
          sleep(10.millis)

    supervised:
      fork(Main6.main(Array.empty))
      sleep(10.millis)
      shutdownLatch.countDown()

    ec shouldEqual 0
  }

  "OxApp.Simple" should "work in failed case" in {
    var ec = Int.MinValue
    var stackTrace = ""

    object Main7 extends OxApp.Simple:
      override def run(using Ox): Unit = throw Exception("oh no")

      override private[ox] def printStackTrace(t: Throwable): Unit =
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        stackTrace = sw.toString

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

    Main7.main(Array.empty)

    ec shouldEqual 1
    assert(stackTrace.contains("oh no"))
  }

  case class FunException(code: Int) extends Exception("")

  import ox.either.*

  "OxApp.WithErrors" should "work in happy case" in {
    var ec = Int.MinValue
    val errOrEc: Either[FunException, ExitCode] = Right(Success)

    object Main8 extends OxApp.WithEitherErrors[FunException]:
      override def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override def handleError(e: FunException): ExitCode = Failure(e.code)

      override def run(args: Vector[String])(using Ox, EitherError[FunException]): ExitCode =
        errOrEc.ok()

    Main8.main(Array.empty)

    ec shouldEqual 0
  }

  "OxApp.WithErrors" should "work in interrupted case" in {
    var ec = Int.MinValue
    val shutdownLatch = CountDownLatch(1)
    val errOrEc: Either[FunException, ExitCode] = Left(FunException(23))

    object Main9 extends OxApp.WithEitherErrors[FunException]:
      override private[ox] def mountShutdownHook(thread: Thread): Unit =
        val damoclesThread = Thread(() => {
          shutdownLatch.await()
          thread.start()
          thread.join()
        })

        damoclesThread.start()

      override def handleError(e: FunException): ExitCode = Failure(e.code)

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override def run(args: Vector[String])(using Ox, EitherError[FunException]): ExitCode =
        forever: // will never finish
          sleep(10.millis)

        errOrEc.ok()

    supervised:
      fork(Main9.main(Array.empty))
      sleep(10.millis)
      shutdownLatch.countDown()

    ec shouldEqual 0
  }

  "OxApp.WithErrors" should "work in failed case" in {
    var ec = Int.MinValue
    val errOrEc: Either[FunException, ExitCode] = Left(FunException(23))
    var stackTrace = ""

    object Main10 extends OxApp.WithEitherErrors[FunException]:
      override def run(args: Vector[String])(using Ox, EitherError[FunException]): ExitCode =
        errOrEc.ok()

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override def handleError(e: FunException): ExitCode = Failure(e.code)

    Main10.main(Array.empty)

    ec shouldEqual 23

    ec = Int.MinValue

    object Main11 extends OxApp.WithEitherErrors[FunException]:
      override def run(args: Vector[String])(using Ox, EitherError[FunException]): ExitCode =
        throw Exception("oh no")

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override private[ox] def printStackTrace(t: Throwable): Unit =
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        stackTrace = sw.toString

      override def handleError(e: FunException): ExitCode = ??? // should not get called!

    Main11.main(Array.empty)

    ec shouldEqual 1
    assert(stackTrace.contains("oh no"))
  }
