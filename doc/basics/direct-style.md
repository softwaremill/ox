# Direct style

## What is direct style?

Direct style is an approach to programming which leverages the built-in control flow constructs of the language as the 
basic building blocks of effectful code. 

In direct style, I/O operations and thread synchronisations are executed as if they were blocking operations, that is 
their result is available as the return value of the appropriate method call. However, specific direct style 
implementations may contain special syntax that is needed to run such operations.

## Compiler/runtime support

Because I/O and synchronisations are "blocking", to make direct style efficient dedicated compiler or runtime support 
is needed. This takes various forms on various platforms: 

* [coroutines in Kotlin](https://kotlinlang.org/docs/coroutines-overview.html), where the compiler transforms functions
  which are "colored" using `suspend` to a finite state machine (using continuation-passing style - CPS)
* similar coloring using `async` is done in [async-await in JavaScript](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Statements/async_function)
* [abilities in Unison](https://www.unison-lang.org/docs/language-reference/abilities-and-ability-handlers) adds an
  algebraic effect system, which is used to guide the CPS transformation 
* the [gears for Scala Native](https://github.com/lampepfl/gears) support relies on a runtime implementation of 
  delimited continuations
* also in Scala, direct style is sometimes supported in a localised fashion, by utilizing macros. See 
  [dotty-cps-async](https://github.com/rssh/dotty-cps-async), 
  [async-await for cats-effect](https://typelevel.org/cats-effect/docs/std/async-await), 
  [zio-direct](https://github.com/zio/zio-direct)

Finally, Java 21 introduced [virtual threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) as
part of Project Loom. The goal of Project Loom is to enable programming in direct style on the JVM with performance 
matching that of reactive and asynchronous libraries, while keeping syntax of Java programs unchanged. 

To achieve that, the JVM runtime manages a pool of platform threads, onto which multiple virtual threads are scheduled. 
Moreover, all blocking operations have been retrofitted to be virtual-thread aware. Virtual threads have a low memory 
footprint, cheap to create and fast to switch between.

## Direct style using Ox

Direct style Scala aims to combine the safety, composability and local reasoning of functional programming with the ease 
of use and performance of imperative programming. This is a departure from a purely-functional style, as implemented by
[cats-effect](https://github.com/typelevel/cats-effect) or [ZIO](https://zio.dev), in favor of running effectful 
computations imperatively.

Note, however, that in all other aspects direct style Scala remains functional: using immutable data structures,
higher order functions, typeclasses, restricting effects, separating code and data, favoring function composition, etc.

Ox uses the above mentioned virtual threads in Java 21 to implement a safe approach to concurrency, combined with 
Go-like channels for inter-thread communication. Moreover, ox supports and proposes an approach to error handling, along 
with multiple utility functions providing a safe resiliency, resource management, scheduling and others.

The overarching goal of ox is enabling safe direct style programming using the power of the Scala 3 language. While 
still in its early days, a lot of functionality is available in ox today!

## Other direct style Scala projects

The wider goal of direct style Scala is enabling teams to deliver working software quickly and with confidence. Our
other projects, including [sttp client](https://sttp.softwaremill.com) and [tapir](https://tapir.softwaremill.com),
also include integrations directly tailored towards direct style.

Moreover, also check out the [gears](https://github.com/lampepfl/gears) project, an experimental multi-platform library
also covering direct style Scala.
