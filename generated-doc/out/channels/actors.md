# Actors

Actors in ox enable invoking methods on an object serially, keeping the behavior as close as possible to a direct 
invocation. That is, even though invocations may happen from multiple threads, they are guaranteed to happen one after 
the other, not concurrently.

Actor invocations are fully type-safe, with minimal overhead. They use [channels](index.md) and 
[scopes](../fork-join.md) behind the scenes.

One of the use-cases is integrating with external APIs, which are represented by an object containing mutable state.
Such integrations must be protected and cannot be accessed by multiple threads concurrently.

```{eval-rst}
.. note::

  Note that actors as described below are a very basic implementation, covering only some use cases for local 
  concurrency. In general, actors are especially useful when working in distributedor clustered systems, or when 
  implementing patterns such as event sourcing. For these use-cases, see the `Pekko <https://pekko.apache.org>`_ 
  project.
```

An actor can be created given any value (representing the actor's state) using `Actor.create`. This creates a fork in 
the current concurrency scope, and a channel (using the `StageCapacity` in scope) for scheduling invocations on the 
actor's logic.

The result is an `ActorRef`, using which invocations can be scheduled using either the `ask` or `tell` methods.

## Ask

`ask` sends an invocation to the actor and awaits for a result. For example:

```scala
import ox.supervised
import ox.channels.*

class Stateful:
  private var counter: Int = 0
  def increment(delta: Int): Int =
    counter += delta
    counter

supervised {
  val ref = Actor.create(new Stateful)

  ref.ask(_.increment(5)) // blocks until the invocation completes
  ref.ask(_.increment(4)) // returns 9
}
```

If a non-fatal exception is thrown by the invocation, it's propagated to the caller, and the actor continues processing
other invocations. Fatal exceptions (e.g. interruptions) are propagated to the enclosing actor's scope, and the actor
closes - trying to create another invocation will throw an exception.

In this approach, actor's internal state usually has to be mutable. For a more functional style, an actor's 
implementation can contain a state machine with a single mutable field, containing the current state; each invocation of
an actor's method can then match on the current state, and calculate the next one.

## Tell

It's also possible to schedule an invocation to be processed in the background using `.tell`. This method only blocks
until the invocation can be sent to the actor's channel, but doesn't wait until it's processed.

Note that any exceptions that occur when handling invocations scheduled using `.tell` will be propagated to the actor's
enclosing scope, and will cause the actor to close.

## Close

When creating an actor, it's possible to specify a callback that will be called uninterruptedly before the actor closes.
Such a callback can be used to release any resources held by the actor's logic. It's called when the actor closes, which
includes closing of the enclosing scope:

```scala
import ox.{never, supervised}
import ox.channels.*

class Stateful:
  def work(howHard: Int): Unit = throw new RuntimeException("boom!")
  def close(): Unit = println("Closing")  

supervised {
  val ref = Actor.create(new Stateful, Some(_.close()))

  // fire-and-forget, exception causes the scope to close
  ref.tell(_.work(5)) 
  
  // preventing the scope from closing
  never
}
```
