# Dictionary

How we use various terms throughout the codebase and the documentation (or at least try to):

* **concurrency scope**: either `supervised` (default) or `scoped` ("advanced")
* a scope **ends**: when unsupervised, the main body is entirely evaluated; when supervised, all user (non-daemon),
  supervised forks complete successfully, or at least one supervised fork fails. When the scope ends, all running
  forks are interrupted
* scope **completes**, once all forks complete and finalizers are run. In other words, the `supervised` or `scoped`
  method returns.
* forks are **started**, and then they are **running**
* forks **complete**: either a fork **succeeds**, or a fork **fails** with an exception
* **cancellation** (`Fork.cancel()`) interrupts the fork and waits until it completes
* scope **body**: the code block passed to a `supervised` or `scoped` method
