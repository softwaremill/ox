# Control flow methods

There are some helper methods which might be useful when writing forked code:

* `forever { ... }` repeatedly evaluates the given code block forever
* `repeatWhile { ... }` repeatedly evaluates the given code block, as long as it returns `true`
* `uninterruptible { ... }` evaluates the given code block making sure it can't be interrupted
