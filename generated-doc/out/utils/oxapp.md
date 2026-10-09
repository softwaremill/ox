# OxApp

To properly handle application interruption and clean shutdown, Ox provides a way to define application entry points
using `OxApp` trait. The application's main `run` function is then executed on a virtual thread, with a root `Ox`
capability provided. 

Here's an example:

```scala
import ox.*
import scala.concurrent.duration.*

object MyApp extends OxApp:
  def run(args: Vector[String])(using Ox): ExitCode =
    forkUser {
      sleep(500.millis)
      println("Fork finished!")
    }
    println(s"Started app with args: ${args.mkString(", ")}!")
    ExitCode.Success
```

When the application receives a SIGINT/SIGTERM, e.g. due to a CTRL+C, the root scope (and hence any child scopes and 
forks) are interrupted. This allows for a clean shutdown: any resources that are attached to scopes, or managed using 
`try-finally` blocks, are released. Application shutdown is handled by adding a `Runtime.addShutdownHook`.

```{warning}
When using `OxApp`, within an Ox-managed fork (thread), do not use `System.exit` or `sys.exit` to shut down your 
application. This will cause a deadlock with the cleanup process. For more details see 
[#368](https://github.com/softwaremill/ox/issues/368).
```

In the code below, the resource is released when the application is interrupted:

```scala
import ox.*

object MyApp extends OxApp:
  def run(args: Vector[String])(using Ox): ExitCode =
    releaseAfterScope:
      println("Releasing ...")
    println("Waiting ...")
    never
```

The `run` function receives command line arguments as a `Vector` of `String`s, a given `Ox` capability and 
has to return an `ox.ExitCode` value which translates to the exit code returned from the program. `ox.ExitCode` is 
defined as:

```scala
enum ExitCode(val code: Int):
  case Success extends ExitCode(0)
  case Failure(exitCode: Int = 1) extends ExitCode(exitCode)
```

There's also a simplified variant of `OxApp` for situations where you don't care about command line arguments. 
The `run` function doesn't take any arguments beyond the root `Ox` capability, expects no `ExitCode` and will 
handle any exceptions thrown by printing a stack trace and returning an exit code of `1`:

```scala
import ox.*

object MyApp extends OxApp.Simple:
  def run(using Ox): Unit = println("All done!")
```

`OxApp` has also a variant that integrates with [either](../basics/error-handling.md#boundary-break-for-eithers) 
blocks for direct-style error handling called `OxApp.WithEitherErrors[E]`. Here, `E` is the type of errors from the 
`run` function that you want to handle. The interesting bit is that `run` function in `OxApp.WithEitherErrors` receives
an `either` block token of type `EitherError[E]` (which itself is an alias for `Label[Either[E, ExitCode]]` as `either` 
operates on boundary/break mechanism). Therefore, it's possible to use `.ok()` combinators directly in the `run` 
function scope. `OxApp.WithEitherErrors` requires that one implements a function that translates application errors
into `ExitCode` instances. Here's an example that always fails and exits with exit code `23`:

```scala
import ox.*
import ox.either.*

sealed trait MyAppError
case class ComputationError(msg: String) extends Exception(msg) with MyAppError

object MyApp extends OxApp.WithEitherErrors[MyAppError]:
  def doWork(): Either[MyAppError, Unit] = Left(ComputationError("oh no"))
  
  def handleError(myAppError: MyAppError): ExitCode = myAppError match {
    case ComputationError(_) => ExitCode.Failure(23)
  }

  def run(args: Vector[String])(using Ox, EitherError[MyAppError]): ExitCode = 
    doWork().ok() // will end the scope with MyAppError as `doWork` returns a Left
    ExitCode.Success
```

## Additional configuration

All `ox.OxApp` instances can be configured by overriding the `def settings: Settings` method. Settings include:

* `interruptedExitCode`: what exit code should be returned by the application once it gracefully shutdowns after it 
  was interrupted (for example Ctrl+C was pressed by the user). By default `0` (graceful shutdown)
* `handleException` and `handleInterruptedException`: callbacks for exceptions that occur when evaluating the 
  application's body, or that are thrown when the application shuts down due to an interruption (SIGINT/SIGTERM).
  By default, the stack traces are printed to stderr, unless a default uncaught exception handler is defined.
* `threadFactory`: the thread factory that is used to create threads in Ox scopes ([[supervised]], [[unsupervised]]
   etc.). Useful e.g. when integrating with third-party libraries to propagate context across (virtual) thread 
   boundaries.
* `shutdownTimeout`: the maximum amount of time a clean shutdown might take. By default 10 seconds. This might
  prevent deadlocks due to usage of `System.exit` in the user's code. After the timeout passes, the application
  will forcibly exit.

Settings can be overridden:

```scala
import ox.*
import scala.concurrent.duration.*

object MyApp extends OxApp:
  override def settings: OxApp.Settings = OxApp.Settings.Default.copy(
    interruptedExitCode = ExitCode.Failure(130)
  )
  
  def run(args: Vector[String])(using Ox): ExitCode =
    sleep(60.seconds)
    ExitCode.Success
```
