package ox

import scala.util.boundary.*
import scala.util.control.NonFatal

enum ExitCode(val code: Int):
  case Success extends ExitCode(0)
  case Failure(exitCode: Int = 1) extends ExitCode(exitCode)

trait OxApp:
  import OxApp.AppSettings

  protected def settings: AppSettings = AppSettings.defaults

  final def main(args: Array[String]): Unit =
    unsupervised {
      val cancellableMainFork = forkCancellable(supervised(handleRun(args.toVector)))

      val interruptThread = new Thread(() => {
        cancellableMainFork.cancel()
        ()
      })

      interruptThread.setName("ox-interrupt-hook")

      mountShutdownHook(interruptThread)

      cancellableMainFork.joinEither() match
        case Left(iex: InterruptedException) => exit(settings.gracefulShutdownExitCode)
        case Left(fatalErr)                  => throw fatalErr
        case Right(exitCode)                 => exit(exitCode)
    }

  /** For testing - trapping System.exit is impossible due to SecurityManager removal so it's just overrideable in tests.
    * @param code
    *   Int exit code
    */
  private[ox] def exit(exitCode: ExitCode): Unit = System.exit(exitCode.code)

  /** For testing - allows to trigger shutdown hook without actually stopping the jvm.
    * @param thread
    *   Thread
    */
  private[ox] def mountShutdownHook(thread: Thread): Unit =
    try Runtime.getRuntime.addShutdownHook(thread)
    catch case _: IllegalStateException => ()

  /** For testing - allows to capture the stack trace printed to the console
    *
    * @param t
    *   Throwable
    */
  private[ox] def printStackTrace(t: Throwable): Unit = t.printStackTrace()

  private[OxApp] final def handleRun(args: Vector[String])(using Ox): ExitCode =
    try run(args)
    catch
      case NonFatal(err) =>
        printStackTrace(err)
        ExitCode.Failure()

  def run(args: Vector[String])(using Ox): ExitCode

object OxApp:

  case class AppSettings(
      /** This value is returned to the operating system as the exit code when the app receives SIGINT and shuts itself down gracefully. */
      gracefulShutdownExitCode: ExitCode = ExitCode.Success
  )

  object AppSettings {
    lazy val defaults: AppSettings = AppSettings()
  }

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

    /** Allows implementor of this trait to translate an error that app finished with into a concrete ExitCode.
      * @param e
      *   E Error type
      * @return
      *   ExitCode
      */
    def handleError(e: E): ExitCode

    /** This template method is to be implemented by abstract classes that add integration for particular error handling data structure of
      * type F[_].
      *
      * @param args
      *   List[String]
      * @return
      *   F[ExitCode]
      */
    def runWithErrors(args: Vector[String])(using Ox): F[ExitCode]

  /** WithEitherErrors variant of OxApp integrates OxApp with an `either` block and allows for usage of `.ok()` combinators in the body of
    * the main function.
    *
    * @tparam E
    *   Error type
    */
  abstract class WithEitherErrors[E] extends WithErrorMode(EitherMode[E]()):

    type EitherError[Err] = Label[Either[Err, ExitCode]]

    override final def runWithErrors(args: Vector[String])(using ox: Ox): Either[E, ExitCode] =
      either[E, ExitCode](label ?=> run(args.toVector)(using ox, label))

    def run(args: Vector[String])(using Ox, EitherError[E]): ExitCode
