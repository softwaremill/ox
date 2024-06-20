package ox

import scala.util.boundary.*
import scala.util.control.NonFatal

enum ExitCode(val code: Int):
  case Success extends ExitCode(0)
  case Failure(exitCode: Int = 1) extends ExitCode(exitCode)

trait OxApp:
  def main(args: Array[String]): Unit =
    unsupervised:
      val cancellableMainFork = forkCancellable(supervised(handleRun(args.toVector)))

      val interruptThread = new Thread(() => {
        cancellableMainFork.cancel()
        ()
      })

      interruptThread.setName("ox-interrupt-hook")

      mountShutdownHook(interruptThread)

      cancellableMainFork.joinEither() match
        case Left(iex: InterruptedException) => exit(0)
        case Left(fatalErr)                  => throw fatalErr
        case Right(exitCode)                 => exit(exitCode.code)

  private[ox] def exit(code: Int): Unit = System.exit(code)

  private[ox] def mountShutdownHook(thread: Thread): Unit =
    try Runtime.getRuntime.addShutdownHook(thread)
    catch case _: IllegalStateException => ()

  private[ox] def printStackTrace(t: Throwable): Unit = t.printStackTrace()

  private[OxApp] final def handleRun(args: Vector[String])(using Ox): ExitCode =
    try run(args)
    catch
      case NonFatal(err) =>
        printStackTrace(err)
        ExitCode.Failure()

  def run(args: Vector[String])(using Ox): ExitCode

object OxApp:
  trait Simple extends OxApp:
    override final def run(args: Vector[String])(using Ox): ExitCode =
      run
      ExitCode.Success

    def run(using Ox): Unit

  trait WithErrors[E] extends OxApp:

    type EitherScope[Err] = Label[Either[Err, ExitCode]]

    override final def run(args: Vector[String])(using ox: Ox): ExitCode =
      either[E, ExitCode](label ?=> run(args)(using ox, label)) match
        case Left(e)   => handleErrors(e)
        case Right(ec) => ec

    def handleErrors(e: E): ExitCode

    def run(args: Vector[String])(using Ox, EitherScope[E]): ExitCode
