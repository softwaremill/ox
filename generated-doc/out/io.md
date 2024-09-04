# I/O

Ox includes the `IO` capability, which is designed to be part of the signature of any method, which performs I/O
either directly or indirectly. The goal is for method signatures to be truthful, and specify the possible side effects,
failure modes and timing in a reasonably precise and practical way. For example:

```scala
import ox.IO

def readFromFile(path: String)(using IO): String = ???
def writeToFile(path: String, content: String)(using IO): Unit = ???
def transform(path: String)(f: String => String)(using IO): Unit =
  writeToFile(path, f(readFromFile(path)))
```

In other words, the presence of a `using IO` parameter indicates that the method might:

* have side effects: write to a file, send a network request, read from a database, etc.
* take a non-trivial amount of time to complete due to blocking, data transfer, etc.
* throw an exception (unless the exceptions are handled, and e.g. transformed into 
  [application errors](basics/error-handling.md))

Quite importantly, the **absence** of `using IO` specifies that the method has **no** I/O side effects (however, it 
might still block the thread, e.g. when using a channel, or have other side effects, such as throwing exceptions,
accessing the current time, or generating a random number). Compiler assists in checking this property, but only to a 
certain degree - it's possible to cheat!

The `IO` capability can be introduced using `IO.unsafe`. Ideally, this method should only be used at the edges of your 
application (e.g. in the `main` method), or when integrating with third-party libraries. Otherwise, the capability
should be passed as an implicit parameter. Such an ideal scenario might not possible, but there's still value in `IO`
tracking: by looking up the usages of `IO.unsafe` it's possible to quickly find the "roots" where the `IO` capability 
is introduced. For example:

```scala
import ox.IO

def sendHTTPRequest(body: String)(using IO): String = ???

@main def run(): Unit = 
  IO.unsafe:
    sendHTTPRequest("Hello, world!")
```

For testing purposes, instead of using `IO.unsafe`, there's a special import which grants the capability within the
scope of the import. By having different mechanisms for introducing `IO` in production and test code, test usages don't
pollute the search results, when verifying `IO.unsafe` usages (which should be as limited as possible). For example:

```scala
import ox.IO
import ox.IO.globalForTesting.given

def myMethod()(using IO): Unit = ???

def testMyMethod(): Unit = myMethod()
```

Take care not to capture the capability e.g. using constructors (unless you are sure such usage is safe), as this might 
circumvent the tracking of I/O operations. Similarly, the capability might be captured by lambdas, which might later be 
used when the IO capability is not in scope. In future Scala and Ox releases, these problems should be detected at 
compile-time using the upcoming capture checker.

## The requireIO compiler plugin

Ox provides a compiler plugin, which verifies at compile-time that the `IO` capability is present when invoking any
methods from the JDK or Java libraries that specify to throw an IO-related exception, such as `java.io.IOException`. 

To use the plugin, add the following settings to your sbt configuration:

```scala
autoCompilerPlugins := true
addCompilerPlugin("com.softwaremill.ox" %% "plugin" % "0.3.4")
```

For scala-cli:

```scala
//> using plugin com.softwaremill.ox:::plugin:0.3.4
```

With the plugin enabled, the following code won't compile:

```scala
import java.io.InputStream

object Test:
  def test(): Unit =
    val is: InputStream = ???
    is.read()

/*
[error] -- Error: Test.scala:8:11
[error] 8 |    is.read()
[error]   |    ^^^^^^^^^
[error]   |The `java.io.InputStream.read` method throws an `java.io.IOException`,
[error]   |but the `ox.IO` capability is not available in the implicit scope.
[error]   |
[error]   |Try adding a `using IO` clause to the enclosing method.
 */
```

You can think of the plugin as a way to translate between the effect system that is part of Java - checked exceptions - 
and the `IO` effect specified by Ox. Note that only usages of Java methods which have the proper `throws` clauses will 
be checked (or of Scala methods, which have the `@throws` annotation). 

```{note}
If you are using a Scala library that uses Java's I/O under the covers, such usages can't (and won't) be 
checked by the plugin. The scope of the plugin is currently limited to the JDK and Java libraries only.
```

### Other I/O exceptions

In some cases, libraries wrap I/O exceptions in their own types. It's possible to configure the plugin to require the
`IO` capability for such exceptions as well. In order to do so, you need to pass the fully qualified names of these 
exceptions as a compiler plugin option, each class in a separate option. For example, in sbt:

```scala
Compile / scalacOptions += "-P:requireIO:com.example.MyIOException"
```

In a scala-cli directive:

```bash
//> using option -P:requireIO:com.example.MyIOException
```

Currently, by default the plugin checks for the following exceptions:

* `java.io.IOException`
* `java.sql.SQLException`

## Potential benefits of tracking methods that perform I/O

Tracking which methods perform I/O using the `IO` capability has the only benefit of giving you method signatures, 
which carry more information. In other words: more type safety. The specific benefits might include:

* better code readability (what does this method do?
* local reasoning (does this method perform I/O?)
* safer refactoring (adding I/O to a previously pure method triggers errors in the compiler, you need to consciously add the capability)
* documentation through types (an IO method can take a longer time, have side-effects)
* possible failure modes (an IO method might throw an exception)
