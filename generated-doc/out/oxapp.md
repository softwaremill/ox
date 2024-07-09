# OxApp

Ox provides a way to define application entry points in the "Ox way" using `OxApp` trait. Starting the app this way comes
with the benefit of the main `run` function being executed on a virtual thread, with a root `Ox` scope provided, 
and application interruption handling built-in. The latter is handled using `Runtime.addShutdownHook` and will interrupt 
the main virtual thread, should the app receive, for example, a SIGINT due to Ctrl+C being issued by the user. 
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

The `run` function receives command line arguments as a `Vector` of `String`s, a given `Ox` capability and has to 
return an `ox.ExitCode` value which translates to the exit code returned from the program. `ox.ExitCode` is defined as:

```scala
enum ExitCode(val code: Int):
  case Success extends ExitCode(0)
  case Failure(exitCode: Int = 1) extends ExitCode(exitCode)
```

There's also a simplified variant of `OxApp` for situations where you don't care about command line arguments. 
The `run` function doesn't take any arguments beyond the root `Ox` scope capability, expects no `ExitCode` and will 
handle any exceptions thrown by printing a stack trace and returning an exit code of `1`:

```scala
import ox.*

object MyApp extends OxApp.Simple:
  def run(using Ox): Unit = println("All done!")
```

`OxApp` has also a variant that integrates with [either](basics/error-handling.md#boundary-break-for-eithers) 
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
    doWork().ok() // will close the scope with MyAppError as `doWork` returns a Left
    ExitCode.Success
```

## Additional configuration

All `ox.OxApp` instances can be configured by overriding the `def settings: AppSettings` method. For now `AppSettings`
contains only the `gracefulShutdownExitCode` setting that allows one to decide what exit code should be returned by 
the application once it gracefully shutdowns after it was interrupted (for example Ctrl+C was pressed by the user).

By default `OxApp` will exit in such scenario with exit code `0` meaning successful graceful shutdown, but it can be 
overridden: 

```scala
import ox.*
import scala.concurrent.duration.*
import OxApp.AppSettings

object MyApp extends OxApp:
  override def settings: AppSettings = AppSettings(
    gracefulShutdownExitCode = ExitCode.Failure(130)
  )
  
  def run(args: Vector[String])(using Ox): ExitCode =
    sleep(60.seconds)
    ExitCode.Success
```
