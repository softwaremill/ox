package ox

import scala.util.boundary.*
import scala.util.control.NonFatal

enum ExitCode(val code: Int):
  case Success extends ExitCode(0)
  case Failure(exitCode: Int = 1) extends ExitCode(exitCode)

/** Extend this trait when defining application entry points. Comes in several variants:
  *
  *   - [[OxApp.Simple]] for applications which don't use command-line arguments
  *   - [[OxApp]] for applications which use command-line arguments
  *   - [[OxApp.WithEitherErrors]] to be able to unwrap `Either`s (see [[either.apply()]]) in the entry point's body. If case of failure,
  *     the applications ends with an error
  *   - [[OxApp.WithErrorMode]] to report errors (which end the application) using other [[ErrorMode]]s
  *
  * The benefit of using `OxApp` compared to normal `@main` methods is that application interruptions is handled properly. A fork in a scope
  * is created to run the application's logic. Interrupting the application (e.g. using CTRL+C) will cause the scope to end and all forks to
  * be interrupted, allowing for a clean shutdown.
  */
trait OxApp:
  import OxApp.AppSettings

  protected def settings: AppSettings = AppSettings.Default

  final def main(args: Array[String]): Unit =
    try
      unsupervised {
        val cancellableMainFork = forkCancellable(supervised(handleRun(args.toVector)))

        val interruptThread = new Thread(() => {
          cancellableMainFork.cancel()
          ()
        })

        interruptThread.setName("ox-interrupt-hook")

        mountShutdownHook(interruptThread)

        cancellableMainFork.joinEither() match
          case Left(_: InterruptedException) => exit(settings.gracefulShutdownExitCode)
          case Left(fatalErr)                => throw fatalErr
          case Right(exitCode)               => exit(exitCode)
      }
    catch
      // if .joinEither is interrupted, the exception will be rethrown, won't be returned as a Left
      case ie: InterruptedException => exit(settings.gracefulShutdownExitCode)

  /** For testing - trapping System.exit is impossible due to SecurityManager removal so it's just overrideable in tests. */
  private[ox] def exit(exitCode: ExitCode): Unit = System.exit(exitCode.code)

  /** For testing - allows to trigger shutdown hook without actually stopping the jvm. */
  private[ox] def mountShutdownHook(thread: Thread): Unit =
    try Runtime.getRuntime.addShutdownHook(thread)
    catch case _: IllegalStateException => ()

  /** For testing - allows to capture the stack trace printed to the console */
  private[ox] def printStackTrace(t: Throwable): Unit = t.printStackTrace()

  private[OxApp] final def handleRun(args: Vector[String])(using Ox): ExitCode =
    try run(args)
    catch
      case NonFatal(err) =>
        printStackTrace(err)
        ExitCode.Failure()

  def run(args: Vector[String])(using Ox): ExitCode
end OxApp

object OxApp:
  /** @param gracefulShutdownExitCode
    *   This value is returned to the operating system as the exit code when the app receives SIGINT and shuts itself down gracefully. In
    *   the [[AppSettings.Default]] settings, the value is `ExitCode.Success` (0). JVM itself returns code `130` when it receives `SIGINT`.
    */
  case class AppSettings(gracefulShutdownExitCode: ExitCode)

  object AppSettings:
    lazy val Default: AppSettings = AppSettings(ExitCode.Success)

  /** Simple variant of OxApp does not pass command line arguments and exits with exit code 0 if no exceptions were thrown.
    */
  trait Simple extends OxApp:
    override final def run(args: Vector[String])(using Ox): ExitCode =
      run
      ExitCode.Success

    def run(using Ox): Unit

  /** WithErrorMode variant of OxApp allows to specify what kind of error handling for the main function should be used. Base trait for
    * integrations.
    *
    * @tparam E
    *   Error type
    * @tparam F
    *   wrapper type for given ErrorMode
    */
  trait WithErrorMode[E, F[_]](em: ErrorMode[E, F]) extends OxApp:
    override final def run(args: Vector[String])(using ox: Ox): ExitCode =
      val result = runWithErrors(args)
      if em.isError(result) then handleError(em.getError(result))
      else ExitCode.Success

    /** Allows implementor of this trait to translate an error that app finished with into a concrete ExitCode. */
    def handleError(e: E): ExitCode

    /** This template method is to be implemented by abstract classes that add integration for particular error handling data structure of
      * type F[_].
      */
    def runWithErrors(args: Vector[String])(using Ox): F[ExitCode]
  end WithErrorMode

  /** WithEitherErrors variant of OxApp integrates OxApp with an `either` block and allows for usage of `.ok()` combinators in the body of
    * the main function.
    *
    * @tparam E
    *   Error type
    */
  abstract class WithEitherErrors[E] extends WithErrorMode(EitherMode[E]()):
    type EitherError[Err] = Label[Either[Err, ExitCode]]

    override final def runWithErrors(args: Vector[String])(using ox: Ox): Either[E, ExitCode] =
      either[E, ExitCode](label ?=> run(args)(using ox, label))

    def run(args: Vector[String])(using Ox, EitherError[E]): ExitCode
end OxApp
