# Interruptions

Ox implements cancellation using Java's interruptions. When a concurrency scope
ends (or when using operations such as `timeout` or `race`), any forks that are
still running are interrupted by calling `Thread.interrupt()` on their backing
(virtual) threads. This mechanism is cooperative: an interruption is only "seen"
by a fork when it is blocked in an interruptible operation (which then throws an
`InterruptedException`), or when the code explicitly checks the thread's
interrupt flag. Hence, two things can go wrong:

* the fork catches, but doesn't propagate the `InterruptedException`, or
* the fork is blocked in an operation which is not interruptible at all.

Both cases are covered below.

## Propagating `InterruptedException`s

When catching exceptions, care must be taken not to catch & fail to propagate an
`InterruptedException`. Doing so will prevent the scope cleanup mechanisms to
make appropriate progress, as the scope won't finish until all started threads
complete.

A good solution is to catch only non-fatal exception using `NonFatal`, e.g.:

```scala mdoc:compile-only
import ox.{forever, fork, supervised}

import org.slf4j.LoggerFactory
import scala.util.control.NonFatal

val logger = LoggerFactory.getLogger(this.getClass)
def processSingleItem(): Unit = ()

supervised {
  fork {
    forever {
      try processSingleItem()
      catch case NonFatal(e) => logger.error("Processing error", e)
    }
  }

  // do something else
}
```

## Which operations are interruptible?

Most blocking operations from the JDK's concurrency toolbox are interruptible,
and respond to interruption by throwing an `InterruptedException`:
[`Object.wait`, `Thread.sleep`,
`Thread.join`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html#interrupt()),
`LockSupport.park`, `ReentrantLock.lockInterruptibly`, blocking queue
operations, and all blocking operations provided by Ox itself (such as receiving
from a [channel](../streaming/channels.md)).

For I/O, the situation is more nuanced:

* **NIO channels** implementing
  [`InterruptibleChannel`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/InterruptibleChannel.html)
  (`FileChannel`, `SocketChannel`, `ServerSocketChannel`, `DatagramChannel`,
  `Pipe` channels) are interruptible, but destructively so: the interrupt
  **closes the channel**, and the blocked thread receives a
  `ClosedByInterruptException`. Beware that for `FileChannel`, interruption
  closes the channel but doesn't abort an in-flight kernel read/write, and an
  interrupted read [has been observed to return normally while silently closing
  the channel](https://bugs.openjdk.org/browse/JDK-6979009) (see also [this
  analysis](https://gamlor.info/posts-output/2019-11-13-file-channel-closes-when-interrupted/en/)).
* **Blocking socket I/O** (`java.net.Socket`, `ServerSocket`, `DatagramSocket`)
  is interruptible **on virtual threads** — which is what Ox's forks run on.
  Since [JEP 444](https://openjdk.org/jeps/444) (Java 21), these blocking
  methods are specified to be interruptible when invoked on a virtual thread:
  the interrupt closes the socket, and the blocked thread receives a
  `SocketException`. Note that this only applies to virtual threads — see below
  for platform threads.
* **`Process.waitFor`** is interruptible, and throws an `InterruptedException`.

As with channels, socket interruption is destructive: after a cancelled read the
socket is closed and can't be reused. That's usually fine for cancellation
purposes, but it does mean an interrupt can't be used to merely "abandon" a
single read attempt.

## Uninterruptible operations

Some blocking operations on the JVM don't respond to interruption at all: the
interrupt flag is set, but the thread stays blocked. There is [no general
technique to unblock such a
thread](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/doc-files/threadPrimitiveDeprecation.html)
— the JDK's official advice is to close the underlying resource from another
thread, which causes the blocked operation to fail with an exception (e.g. an
`IOException`). Note that this only works for resources supporting asynchronous
close — sockets, NIO channels, subprocesses (via destroy) — but not for e.g.
`FileInputStream`, where closing does not unblock an in-progress read.

When a fork is blocked in such an operation, ending a scope (or cancelling via
`timeout`/`race`) will stall until the operation completes on its own. Known
uninterruptible operations include:

* **Classic `java.io` stream reads & writes**: `InputStream.read` /
  `OutputStream.write` and their subclasses & wrappers — `System.in`,
  `FileInputStream`, `FileOutputStream`, `RandomAccessFile`, `Reader`s/`Writer`s
  over them. This is intended, permanent behavior: the request to make such
  reads interruptible was [closed as "Won't
  Fix"](https://bugs.openjdk.org/browse/JDK-4514257). This applies also on
  virtual threads, for non-socket sources.
* **`System.out.println` (and other console writes), when blocked**: printing
  blocks if the console, pipe or terminal on the other end isn't consuming data
  (e.g. a full pipe buffer). The write is a classic stream write, so it's
  [uninterruptible](https://bugs.openjdk.org/browse/JDK-4514257); moreover,
  since `PrintStream` methods are `synchronized`, other threads calling
  `println` will then block uninterruptibly on the monitor as well.
* **Socket I/O on platform threads**: unlike on virtual threads, blocking
  `java.net.Socket` reads/writes/accepts on a platform thread [do not respond to
  interruption](https://wiki.sei.cmu.edu/confluence/display/java/THI04-J.+Ensure+that+threads+performing+blocking+operations+can+be+terminated)
  — only closing the socket unblocks them. This matters for code running outside
  of Ox scopes, or in libraries that manage their own (platform) thread pools.
* **DNS resolution**: `InetAddress.getByName` delegates to the OS resolver, with
  [no way to set a timeout or interrupt the
  lookup](https://bbossola.wordpress.com/2015/10/23/java-no-timeout-on-dns-resolution/)
  from Java.
* **JDBC calls**: e.g. `Statement.execute` [ignores
  interrupts](https://bugs.openjdk.org/browse/JDK-6393812). The sanctioned
  cancellation mechanism, `Statement.cancel`, is a cooperative, server-side
  protocol: the blocked thread resumes only once the database sends back an
  error reply — so [it won't help if the server is hung or the network is
  broken](https://docs.oracle.com/en/database/oracle/oracle-database/18/jjdbc/JDBC-troubleshooting.html).
  Use `setQueryTimeout` / `setNetworkTimeout` defensively.
* **Acquiring locks**: entering a `synchronized` block/method, as well as
  [`ReentrantLock.lock()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html#lock()),
  block ignoring interrupts. If lock acquisition should be cancellable, use
  [`lockInterruptibly()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html#lockInterruptibly()).
* **Reading a subprocess's output/error streams**: these are classic streams
  (see above); the reliable way to unblock a reader is to [destroy the
  process](https://www.dontpanicblog.co.uk/2023/05/07/handling-blocked-process-output-stream/).
* **Memory-mapped file access**: touching a `MappedByteBuffer` page that must be
  faulted in from disk blocks inside the kernel, below any Java-level
  interruption mechanism; such [page faults can take hundreds of
  milliseconds](https://groups.google.com/g/mechanical-sympathy/c/yL4Yaedgqg4)
  (or longer, e.g. on network filesystems).

To make cancellation work with such operations, run them through the
abandon-on-interrupt utilities described below, which delegate the blocking
operation to a detached thread — so that waiting becomes interruptible. Note
that regular [scope-managed resources](../utils/resources.md) (`useInScope`,
`releaseAfterScope`) are released only after all forks complete, so they won't
unblock a fork stuck in uninterruptible I/O.

## Abandoning uninterruptible operations

Since an uninterruptible operation can't be cancelled, the way out is to not
wait for it uninterruptibly: the operation is delegated to a **detached thread**
— an unmanaged virtual thread, running outside of any concurrency scope, which
is never joined — while the calling fork awaits the result interruptibly. On
interruption, the operation is **abandoned**: its result is given up (the fork
proceeds with an `InterruptedException`), while the detached thread keeps
running until the operation completes — its result is then discarded. Ox
provides three utilities implementing this pattern:

* `abandonOnInterrupt(op)` — runs a one-off blocking operation on a detached
  thread, awaiting its result interruptibly. An optional second parameter list
  provides cleanup, started (on another detached thread) when the wait is
  abandoned: `abandonOnInterrupt(statement.execute())(connection.close())`. For
  resources supporting asynchronous close, the cleanup actively unblocks the
  abandoned operation as well — nothing is leaked.
* `abandonOnInterruptReads(inputStream)` — wraps an `InputStream`, so that reads
  become interruptible; chunks are read by a detached thread and passed through
  a rendezvous channel. An interrupted read doesn't lose the in-flight chunk —
  it's returned by the next read. With `closeOnAbandon = true`, an interrupted
  read additionally closes the underlying stream, and the wrapper becomes
  permanently closed.
* `abandonOnInterruptWrites(outputStream)` — the same for writes: the actual
  writing happens on a detached thread, and the wrapper's
  `write`/`flush`/`close` block interruptibly. This also covers writes blocked
  on a full pipe — the case of a stuck `println`.

For example, reading from stdin (which can neither be interrupted nor
meaningfully closed) in a way that works with Ox's timeouts and scope
cancellation:

```scala mdoc:compile-only
import ox.*
import scala.concurrent.duration.*

// one process-wide wrapper: multiple wrappers over System.in would compete for input
lazy val stdin = abandonOnInterruptReads(System.in)

supervised {
  val firstByte: Option[Int] = timeoutOption(1.second)(stdin.read())
  println(s"Read: $firstByte")
}
```

If the operation is abandoned, the detached thread remains blocked until the
underlying operation completes — for stdin, possibly for the application's
lifetime. This is the deliberate trade-off of the pattern: a (cheap, virtual)
thread is potentially left behind, in exchange for interruptibility. When the
underlying resource supports asynchronous close (sockets, subprocesses via
destroy), prefer passing `onAbandon` cleanup / `closeOnAbandon = true`, which
unblocks the detached thread too. Also note that abandoned operations keep
running — including their side effects (e.g. an abandoned JDBC query keeps
executing on the server; use `Statement.cancel`-based cleanup in `onAbandon`
where relevant).

Alternatively, for terminal I/O specifically, libraries that access stdin via
JNI/JNA, such as [JLine 3](https://jline.org/docs/intro), can use raw mode with
non-blocking or timeout-based reads, avoiding the detached-thread trade-off
altogether.
