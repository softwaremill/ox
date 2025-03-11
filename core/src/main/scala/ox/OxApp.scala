package ox

import scala.util.boundary.*
import scala.util.control.NonFatal
import java.util.concurrent.ThreadFactory

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
  * is created to run the application's logic. Interrupting the application (by sending SIGINT/SIGTERM, e.g. using CTRL+C) will cause the
  * scope to end and all forks to be interrupted, allowing for a clean shutdown.
  *
  * That way, any resources that have been allocated and attached to scopes, or that are managed using `try-finally` blocks inside forks,
  * will be released properly.
  *
  * Certain aspects of exception handling can be configured using [[OxApp.Settings]] and overriding the `settings` method.
  *
  * The application's code is specified in a `run` method, which has the [[Ox]] capability granted: to fork asynchronously computations, and
  * register clean up of resources
  */
trait OxApp:
  protected def settings: OxApp.Settings = OxApp.Settings.Default

  final def main(args: Array[String]): Unit =
    try
      oxThreadFactory.unsupervisedWhere(settings.threadFactory) {
        val cancellableMainFork = forkCancellable {
          try supervised(run(args.toVector))
          catch
            case NonFatal(e) =>
              settings.handleException(e)
              ExitCode.Failure()
            case e: InterruptedException =>
              settings.handleInterruptedException(e)
              settings.interruptedExitCode
        }

        // on shutdown, the above fork is cancelled, causing interruption
        val interruptThread = new Thread(() => cancellableMainFork.cancel().discard)
        interruptThread.setName("ox-interrupt-hook")
        mountShutdownHook(interruptThread)

        cancellableMainFork.joinEither() match
          case Left(fatal)     => throw fatal // non-fatal and IE are already handled
          case Right(exitCode) => exit(exitCode)
      }
    catch
      // if .joinEither is interrupted, the exception will be rethrown, won't be returned as a Left
      case _: InterruptedException => exit(settings.interruptedExitCode)

  /** For testing - trapping System.exit is impossible due to SecurityManager removal so it's just overrideable in tests. */
  private[ox] def exit(exitCode: ExitCode): Unit = System.exit(exitCode.code)

  /** For testing - allows to trigger shutdown hook without actually stopping the jvm. */
  private[ox] def mountShutdownHook(thread: Thread): Unit =
    try Runtime.getRuntime.addShutdownHook(thread)
    catch case _: IllegalStateException => ()

  def run(args: Vector[String])(using Ox): ExitCode
end OxApp

object OxApp:
  /** Settings for an [[OxApp]]. Defaults are defined in [[Settings.Default]].
    *
    * @param interruptedExitCode
    *   This value is returned to the operating system as the exit code when the app receives SIGINT/SIGTERM and shuts itself down
    *   gracefully. By default, the value is [[ExitCode.Success]]. JVM itself returns code `130` when it receives `SIGINT`.
    * @param handleInterruptedException
    *   Callback used for the interrupted exception that might be thrown by the application's body, w.g. when the the application is
    *   interrupted using SIGINT/SIGTERM. By default, the handler looks for any exceptions that are not instances of
    *   [[InterruptedException]] (as this is considered part of "normal" shutdown process), and prints their stack trace to stderr (unless a
    *   default uncaught exception handler is set).
    * @param handleException
    *   Callback used for exceptions that are thrown by the application's body, causing the application to terminate with a failed
    *   [[ExitCode]]. By default the exception's stack trace is printed to stderr (unless a default uncaught exception handler is set).
    * @threadFactory
    *   The thread factory that is used to create threads in Ox scopes ([[supervised]], [[unsupervised]] etc.). Useful e.g. when integrating
    *   with third-party libraries to propagate context across (virtual) thread boundaries.
    */
  case class Settings(
      interruptedExitCode: ExitCode,
      handleInterruptedException: InterruptedException => Unit,
      handleException: Throwable => Unit,
      threadFactory: ThreadFactory
  )

  object Settings:
    val DefaultLogException: Throwable => Unit = (t: Throwable) =>
      val defaultHandler = Thread.getDefaultUncaughtExceptionHandler
      if defaultHandler != null then defaultHandler.uncaughtException(Thread.currentThread(), t) else t.printStackTrace()

    def defaultHandleInterruptedException(logException: Throwable => Unit): InterruptedException => Unit = (t: InterruptedException) =>
      // inspecting both the top-level and any suppressed exceptions
      for t2 <- t.getSuppressed.toList do
        t2 match
          case _: InterruptedException => // skip
          case _                       => logException(t2)

    val Default: Settings =
      Settings(ExitCode.Success, defaultHandleInterruptedException(DefaultLogException), DefaultLogException, Thread.ofVirtual().factory())
  end Settings

  /** Simple variant of OxApp does not pass command line arguments and exits with exit code 0 if no exceptions were thrown. */
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
    override final def run(args: Vector[String])(using Ox): ExitCode =
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

    override final def runWithErrors(args: Vector[String])(using Ox): Either[E, ExitCode] =
      either[E, ExitCode](run(args))

    def run(args: Vector[String])(using Ox, EitherError[E]): ExitCode
end OxApp
