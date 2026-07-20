# Dictionary

How we use various terms throughout the codebase and the documentation (or at least try to):

Scopes:
* **concurrency scope**: either `supervised` (default), `supervisedError` (permitting application errors), 
  or `unsupervised`
* scope **body**: the code block passed to a concurrency scope (the `supervised`, `supervisedError` or `unsupervised` 
  method)

Types of forks:
* supervised / unsupervised
* daemon / user
* optionally, recognizing application errors

Fork lifecycle:
* within scopes, asynchronously running **forks** can be **started**
* after being started a fork is **running**
* then, forks **complete**: either a fork **succeeds** with a value, or a fork **fails** with an exception
* external **cancellation** (`Fork.cancel()`) interrupts the fork and waits until it completes; interruption uses
  JVM's mechanism of injecting an `InterruptedException`
* forks are **supervised** if they are run in a `supervised` scope, and not explicitly unsupervised (that is, started 
  using `forkUnsupervised` or `forkCancellable`)

Scope lifecycle:
* a scope **ends**: when unsupervised, the scope's body is entirely evaluated; when supervised, all user (non-daemon) &
  supervised forks complete successfully, or at least one user/daemon supervised fork fails, or an application error
  is reported. When the scope ends, all forks that are still running are cancelled
* scope **completes**, once all forks complete and finalizers are run; then, the `supervised`, `supervisedError` or 
  `unsupervised` method returns.
* a **resource scope** (created using `resourceScope`) allows registering resources, which are released when the 
  scope completes; it is not a concurrency scope: forks cannot be started, and there's no cancellation. Every 
  concurrency scope is also a resource scope

Errors:
* fork **failure**: when a fork fails with an exception
* **application error**: forks might successfully complete with values which are considered application-level errors;
  such values are reported to the enclosing scope and cause the scope to end

Other:
* **computation combinator**: a method which takes user-provided functions and manages their execution, e.g. using 
  concurrency, interruption, and appropriately handling errors; examples include `par`, `race`, `retry`, `timeout`
* **detached thread**: an unmanaged virtual thread, running outside of any concurrency scope, which is never joined;
  used to perform uninterruptible blocking operations on behalf of forks (see `abandonOnInterrupt`)
* **abandoning** an operation: when a fork waiting for the result of a blocking, uninterruptible operation is
  interrupted, the operation's result is given up: the operation keeps running on its detached thread (as it can't be
  interrupted), and its eventual result, or exception, is discarded

Channels:
* **values** can be **sent** to a channel, or **received** from a channel

Flows:
* when **run**, a flow **emits** **elements**