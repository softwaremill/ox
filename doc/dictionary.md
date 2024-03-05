# Dictionary

How we use various terms throughout the codebase and the documentation (or at least try to):

Scopes:
* **concurrency scope**: either `supervised` (default), `supervisedError` (permitting application errors), 
  or `scoped` ("advanced")
* scope **body**: the code block passed to a concurrency scope (the `supervised` or `scoped` method)

Fork lifecycle:
* within scopes, asynchronously running **forks** can be **started**
* after being started a fork is **running**
* then, forks **complete**: either a fork **succeeds** with a value, or a fork **fails** with an exception
* external **cancellation** (`Fork.cancel()`) interrupts the fork and waits until it completes; interruption uses
  JVM's mechanism of injecting an `InterruptedException`

Scope lifecycle:
* a scope **ends**: when unsupervised, the scope's body is entirely evaluated; when supervised, all user (non-daemon) &
  supervised forks complete successfully, or at least one user/daemon supervised fork fails, or an application error
  is reported. When the scope ends, all forks that are still running are cancelled
* scope **completes**, once all forks complete and finalizers are run; then, the `supervised` or `scoped`
  method returns.

Errors:
* fork **failure**: when a fork fails with an exception
* **application error**: forks might successfully complete with values which are considered application-level errors;
  such values are reported to the enclosing scope and cause the scope to end
