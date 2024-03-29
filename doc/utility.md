# Utilities

In addition to concurrency, error handling and resiliency features, ox includes some utility methods, which make writing
direct-style Scala code more convenient. When possible, these are `inline` methods, hence without any runtime overhead:

* `uninterruptible { ... }` evaluates the given code block making sure it can't be interrupted
* `.discard` extension method evalutes the given code block and discards its result, avoiding "discarded non-unit value"
  warnings
