# Comparing Ox & functional effects

Ox is often compared and contrasted to functional effect systems. And for a good reason: libraries such as
[cats-effect](https://typelevel.org/cats-effect/) or [ZIO](https://zio.dev) are often regarded as the gold standard when 
it comes to providing compile-time safety, error handling or composability. They represent purely functional 
programming, where programs are defined in terms of lazily evaluated computation descriptions. Such descriptions form
monads, and need monadic operators to compose them. The specific programming styles include tagless-final (in case of
cats-effect), fused monad (in case of ZIO), or algebraic effects (in case of [Kyo](https://getkyo.io/)).

However, using functional effect systems has its drawbacks and brings specific tradeoffs. You can find a deeper dive
on how functional effects and direct style compare in the following talks:

* [Unwrapping IO: is it a path that you want to follow?](https://www.youtube.com/watch?v=qR_Od7qbacs)
* [Concurrency in Scala and on the JVM](https://www.youtube.com/watch?v=6RYn6mgq77s)
* [Effects: To Be Or Not To Be?](https://www.youtube.com/watch?v=sDnNjtkoUVs)

Here's a quick summary of some of the differences, from the perspective of Ox. First off, what we gain when using Ox:

* Simpler syntax: we can use the Scala syntax directly to work with effectful computations, as compared to the
  monadic syntax of functional effects. For example, composing two computations in functional effects amounts to
  invoking `.flatMap` on the computation descriptions, while in direct style it's just two statements one after another
  (separated by an invisible `;`). This makes the code more readable, with less syntactic noise.
* Lower learning curve: the direct style is familiar to most programmers, while the monadic style requires understanding
  and getting used to. Composing programs in terms of lazily evaluated descriptions represented as values, instead of
  "directly" requires a mental switch, which is not always straightforward.
* Better debugability: stack traces in functional effect systems are often not very useful, and carry little information
  helping to pinpoint the problem. In direct style, we get proper, "normal" stack traces.
* No virality: "wrappers" such as `Future` or the `IO` data type are "viral": once we call a method returning an `IO`,
  our method should return an `IO` as well. This is not always necessary in direct style.
* Ability to use built-in control flow constructs: in direct style, we can once again use `if`, `for`, `while`, `try` 
  and other built-in control flow constructs. In functional effects, we need to use their special versions, which are
  functional-effect-aware. A prime example of such operator is `traverse`.

What we retain:

* Fearless concurrency: both approaches offer high-level APIs for concurrency, such as `par` or `race`, as well as 
  lower level APIs, to create and manage fibers/forks manually, along with their lifecycles.
* Supervision: both approaches allow supervising created fibers/forks, through some form of structured concurrency,
  and being notified whenever a fiber/fork fails, so that no errors go unnoticed.

What we partially loose:

* Principled error handling: the type signatures of methods when using functional effects are often more precise, when
  it comes to the type of errors, that the computation might end up with. In case of cats-effect, the presence of `IO`
  signals that the computation might involve blocking operations, which might throw exceptions. In case of ZIO, error
  handling is especially well-designed, and we get full information on the type of errors. Ox proposes using `Either`s
  to represent application-level errors, however there's no tracking of exceptions, blocking or IO operations (at least 
  yet - that's an area we'd like to improve).
* Dedicated resource data type: while in Ox we do have various methods which e.g. attach resources to scopes, it's still
  possible to use resources in an unsafe way - the compiler won't warn us about that. In functional effects, resources
  are typically represented using a dedicated data type, which ensures that they are used safely. Of course, this is 
  only useful if proper integration libraries are provided which expose resources using the appropriate data types, as
  otherwise without proper discipline it's still possible to use resources unsafely.
* Principled interruptions: in Ox and direct style on the JVM in general, we rely on the JVM interruption mechanism, 
  which relies on injecting `IntereruptedException`s. Poorly written libraries, or simply bugs in our code, might 
  intercept such exceptions, and e.g. log them, instead of rethrowing. Using `NonFatal` instead of catch-alls is a way
  to avoid these problems, but again, this relies on discipline. On the other hand, in functional effect systems, 
  interruptions are signalled using an out-of-bound mechanism, which can't be intercepted and ignored.
* The representation of computations is no longer uniform. In functional effects, all computations are always 
  represented as lazily evaluated descriptions. In direct style, and this includes Ox as well, sometimes we need to pass
  lazily evaluated code to some method which needs to control the passed code's evaluation. Hence, the eager/lazy
  distinction needs to be done manually.

What we loose:

* Referential transparency (RT) and "fearless refactoring": since computations are no longer represented as values, some 
  refactorings are no longer safe (e.g. we cannot extract computations to `val`s). However, solving this problem might
  amount to using `def`s for side-effecting computations. The exact benefits of RT are still being discussed, with 
  various ideas of providing similar guarantees in direct style (e.g. by tracking suspensions).

To finish off, it's an ongoing research process to understand the fundamental benefits that functional effect systems 
bring, and to pinpoint which of their characteristics are essential. We're constantly on the lookout for use-cases, 
which can only be written elegantly or safely using one style, and not the other. If you do have such a use-case, please
[share on the forum](https://softwaremill.community)!
