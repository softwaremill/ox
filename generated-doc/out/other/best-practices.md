# Best practices

While working on Ox and integrating ox into our other open-source projects, we found a couple of patterns and best
practices which might be useful for anybody starting their journey with direct-style Scala and ox.

## Make scopes as small as possible

If you end up using concurrency scopes such as `supervised`, make sure that their lifetime is as short as possible. In
some cases it might be necessary to start a "global" scope (e.g. for application-wide, long-running tasks), but even
if so, don't let the global scope leak to any other parts of your code, and isolate its usage, e.g. using 
[actors](../utils/actors.md).

For all other tasks, create short-lived scopes, which handle a single request, message from a queue or a single job
instance.

## Integrate with callback-based APIs using channels

Callback-based APIs, including "reactive" ones, are by their nature non-structured, and don't play well with 
structured concurrency. For such cases, [channels](../streaming/channels.md) are an ideal tool. Sending or receiving to/from 
a channel doesn't require any context, and can be done from any thread. On the other hand, processing the data that
is on the channel often involves concurrency and creating thread, which can be then done in a structured way.

Note, however, that channel operations are potentially blocking. If you can't block, consider using a `select` with 
a default clause.

## Use `using Ox` sparingly

Passing the `Ox` capability gives the method the power to start new threads - which can be a dangerous tool! The goal
of structured concurrency is to localise thread creation as much as possible, and disallow methods which create a
thread as an effect. `using Ox` partially circumvents this guarantee, hence use this with caution, and pay attention
not to pass it through several layers of method calls, which might make the code hard to understand.

## Use flows instead of channels

Transforming channels directly might lead to excessive concurrency, as each transformation typically starts a 
background fork, processing the data and sending it to a new channel. While this still performs well, as creating 
virtual threads & channels is cheap, it might incur an unnecessary overhead. 

Instead, you can use [flows](../streaming/flows.md) and their high-level API, which allows inserting asynchronous
boundaries when necessary, but otherwise runs the subsequent processing stages on the same thread.

## Avoid returning `Fork`

Accidental concurrency is often cited as a problem with using `Future`s: if you call two methods which return a 
`Future`, they will run concurrently, even though you might have never intended that. The same problem can occur if 
a method returns Ox's `Fork`. Hence, avoid returning `Fork`s from methods. Instead, model concurrency on the caller's
side - if something should be run in parallel, the caller can do so, using `supervised` and `fork`, and by calling
blocking methods within the forks.

## Debugging virtual threads

Virtual threads are normally not visible when using tools such as `jstack` or IntelliJ's debugger. To inspect their
stack traces, you'll need to create a thread dump to a file using `jcmd [pid] Thread.dump_to_file [file]`, or use
Intellij's thread dump utility, when paused in the debugger.