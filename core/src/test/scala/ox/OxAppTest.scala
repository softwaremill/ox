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

    object Main10 extends OxApp:
      override def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      def run(args: Vector[String])(using Ox): ExitCode = Success

    Main10.main(Array.empty)

    ec shouldEqual 0
  }

  "OxApp" should "work in interrupted case" in {
    var ec = Int.MinValue
    val shutdownLatch = CountDownLatch(1)

    object Main20 extends OxApp:
      override private[ox] def mountShutdownHook(thread: Thread): Unit =
        val damoclesThread = Thread(() => {
          shutdownLatch.await()
          // simulating interrupt by running the shutdown hook
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
      fork(Main20.main(Array.empty))
      sleep(10.millis)
      shutdownLatch.countDown()

    ec shouldEqual 0
  }

  "OxApp" should "work in failed case" in {
    // failure by returning a non-0 status code

    var ec = Int.MinValue
    var stackTrace = ""

    object Main30 extends OxApp:
      override def run(args: Vector[String])(using Ox): ExitCode =
        Failure(23)

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

    Main30.main(Array.empty)

    ec shouldEqual 23

    ec = Int.MinValue

    // failure by throwing an exception

    object Main31 extends OxApp:
      override def run(args: Vector[String])(using Ox): ExitCode =
        throw Exception("oh no")

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override protected def settings: OxApp.Settings = super.settings.copy(handleException = { t =>
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        stackTrace = sw.toString
      })

    Main31.main(Array.empty)

    ec shouldEqual 1
    assert(stackTrace.contains("oh no"))

    // failure by throwing an exception in a user fork

    object Main32 extends OxApp:
      override def run(args: Vector[String])(using Ox): ExitCode =
        forkUser(throw Exception("oh no"))
        Success

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override protected def settings: OxApp.Settings = super.settings.copy(handleException = { t =>
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        stackTrace = sw.toString
      })

    Main32.main(Array.empty)

    ec shouldEqual 1
    assert(stackTrace.contains("oh no"))
  }

  "OxApp" should "report any non-interrupted exceptions that occur during shutdown" in {
    var ec = Int.MinValue
    val shutdownLatch = CountDownLatch(1)
    var handledExceptions: List[Throwable] = Nil

    object Main40 extends OxApp:
      override private[ox] def mountShutdownHook(thread: Thread): Unit =
        val damoclesThread = Thread(() => {
          shutdownLatch.await()
          // simulating interrupt by running the shutdown hook
          thread.start()
          thread.join()
        })

        damoclesThread.start()

      override private[ox] def exit(exitCode: ExitCode): Unit = ec = exitCode.code

      override protected def settings: OxApp.Settings =
        OxApp.Settings.Default
          .copy(handleInterruptedException =
            OxApp.Settings.defaultHandleInterruptedException(t => handledExceptions = t :: handledExceptions)
          )

      def run(args: Vector[String])(using Ox): ExitCode =
        releaseAfterScope:
          throw new Exception("bye!")

        never

    supervised:
      fork(Main40.main(Array.empty))
      sleep(10.millis)
      shutdownLatch.countDown()

    ec shouldBe 0 // simulated interruption
    handledExceptions.map(_.getMessage) shouldBe List("bye!")
  }

  "OxApp.Simple" should "work in happy case" in {
    var ec = Int.MinValue

    object Main50 extends OxApp.Simple:
      override def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override def run(using Ox): Unit = ()

    Main50.main(Array.empty)

    ec shouldEqual 0
  }

  "OxApp.Simple" should "work in interrupted case" in {
    var ec = Int.MinValue
    val shutdownLatch = CountDownLatch(1)

    object Main60 extends OxApp.Simple:
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
      fork(Main60.main(Array.empty))
      sleep(10.millis)
      shutdownLatch.countDown()

    ec shouldEqual 0
  }

  "OxApp.Simple" should "work in failed case" in {
    var ec = Int.MinValue
    var stackTrace = ""

    object Main70 extends OxApp.Simple:
      override def run(using Ox): Unit = throw Exception("oh no")

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override protected def settings: OxApp.Settings = super.settings.copy(handleException = { t =>
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        stackTrace = sw.toString
      })

    Main70.main(Array.empty)

    ec shouldEqual 1
    assert(stackTrace.contains("oh no"))
  }

  case class FunException(code: Int) extends Exception("")

  import ox.either.*

  "OxApp.WithErrors" should "work in happy case" in {
    var ec = Int.MinValue
    val errOrEc: Either[FunException, ExitCode] = Right(Success)

    object Main80 extends OxApp.WithEitherErrors[FunException]:
      override def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override def handleError(e: FunException): ExitCode = Failure(e.code)

      override def run(args: Vector[String])(using Ox, EitherError[FunException]): ExitCode =
        errOrEc.ok()

    Main80.main(Array.empty)

    ec shouldEqual 0
  }

  "OxApp.WithErrors" should "work in interrupted case" in {
    var ec = Int.MinValue
    val shutdownLatch = CountDownLatch(1)
    val errOrEc: Either[FunException, ExitCode] = Left(FunException(23))

    object Main90 extends OxApp.WithEitherErrors[FunException]:
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
      fork(Main90.main(Array.empty))
      sleep(10.millis)
      shutdownLatch.countDown()

    ec shouldEqual 0
  }

  "OxApp.WithErrors" should "work in failed case" in {
    var ec = Int.MinValue
    val errOrEc: Either[FunException, ExitCode] = Left(FunException(23))
    var stackTrace = ""

    object Main100 extends OxApp.WithEitherErrors[FunException]:
      override def run(args: Vector[String])(using Ox, EitherError[FunException]): ExitCode =
        errOrEc.ok()

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override def handleError(e: FunException): ExitCode = Failure(e.code)

    Main100.main(Array.empty)

    ec shouldEqual 23

    ec = Int.MinValue

    object Main101 extends OxApp.WithEitherErrors[FunException]:
      override def run(args: Vector[String])(using Ox, EitherError[FunException]): ExitCode =
        throw Exception("oh no")

      override private[ox] def exit(exitCode: ExitCode): Unit =
        ec = exitCode.code

      override protected def settings: OxApp.Settings = super.settings.copy(handleException = { t =>
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        stackTrace = sw.toString
      })

      override def handleError(e: FunException): ExitCode = ??? // should not get called!

    Main101.main(Array.empty)

    ec shouldEqual 1
    assert(stackTrace.contains("oh no"))
  }
