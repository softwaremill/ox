# 6. Actors

Date: 2024-03-26

## Context

Motivated by the Kafka integration, it's often useful to call methods on an object with guaranteed serialisation of
access, just as it happens in actors, which protect their mutable state.

## Decision

The current implementation of actors is very simple, and allows sending any thunk to be executed given the current
actor's state. This forces the internal state to be mutable. Such an approach was chosen because of its simplicity,
and how well it fits the motivating Kafka use-case, but it might need revisiting once more use-cases arise.

An alternative implementation would force each actor invocation to return the updated actor state, in addition to
the value that should be returned to the caller (if any). However, it's not clear then how to combine this with
the type-safe syntax of invoking actors (or "sending messages" to them). For each method `T.m(args ...): U` that is
accessible via `ActorRef[T]`, the actor itself would need to have a `TA.ma(args ...): S => (U, S)` method, where `S` is 
the actor's state. The fact that the `T` and `TA` types "match" in this way could be probably verified using a macro, 
but would be harder to implement by users and more complex.

While the idea is that the thunks passed to `ActorRef.ask` and `ActorRef.tell` should invoked a single method on the
actor's interface (similar to "sending a message"), this is not actually verified. As an improvement, these methods 
could be changed to a macro that would verify the shape of the lambda passed to them:

```scala
def doAsk[T, U: Type](f: Expr[T => U], c: Expr[Sink[MethodInvocation]])(using Quotes): Expr[U] =
  import quotes.reflect.*
  '{
    val cf = new CompletableFuture[U]()
    val onResult = (v: Any) => { val _ = cf.complete(v.asInstanceOf[U]) }
    val onException = (e: Throwable) => { val _ = cf.completeExceptionally(e) }
    $c.send(${
      f.asTerm match {
        case Inlined(_, _, Block(List(DefDef(_, _, _, Some(Apply(Select(_, method), parameters)))), _)) =>
          '{ MethodInvocation(${ Expr(method) }, ${ Expr.ofList(parameters.map(_.asExpr)) }, onResult, onException) }
        case _ => report.errorAndAbort(s"Expected a method call in the form _.someMethod(param1, param2), but got: ${f.show}")
      }
    })
    cf.get()
  }
```

Another limitation of this implementation is that it's not possible to schedule messages to self, as using the actor's
`ActorRef` from within the actor's implementation can easily lead to a deadlock (always, if the invocation would be an
`ask`, and with some probability if it would be a `tell` - when the actor's channel would become full).

Finally, error handling might be implemented differently - so that each exception thrown by the actor's methods would
be propagated to the actor's enclosing scope, and would close the actor's channel. While this is the only possibility
in case of `.tell`, as otherwise the exception would go unnoticed, in case of `.ask` only fata exceptions are propagated
this way. Non-fatal ones are propagated to the caller, keeping with the original assumption that using an actor should
be as close as possible to calling the method directly (which would simply propagate the exception).
