# 4. Channels: safe/unsafe Operations

Date: 2024-02-28

## Context

Channel operations such as `send`, `receive`, `select`, `close` etc. might fail because a channel is closed. How should
this be signalled to the user?

## Decision

We decided to have two variants of the methods:

* default: `send`, `receive` etc., which throw an exception, when the channel is closed
* safe: `sendSafe`, `receiveSafe` etc., which return a `ChannelClosed` value, when the channel is closed

The "safe" variants are more performant: no stack trace is created, when the channel is closed. They are used by all
channel combinators (such as `map`, `filter` etc.), to detect and propagate the errors downstream.

### Why not `Either` or `Try`?

To avoid allocations on each operation (e.g. receive). Channels might be on the "hot path" and they might be important 
for performance. Union types provide a nice alternative here.

Even with `Either`, though, if e.g. `send` had a signature `Either[ChannelClosed, Unit]`, discarding the result would 
at most be a warning (not in all cases), so potentially an error might go unnoticed.

### Why is the default to throw?

Let's consider `send`. If the default would be `send(t: T): ChannelClosed | Unit`, with an additional exception-throwing 
variant `sendUnsafe(t: T): Unit`, then the API would be quite surprising.

Coming to the library as a new user, they could just call send / receive. The compiler might warn them in some cases 
that they discard the non-unit result of `send`, but (a) would they pay attention to those warnings, and (b) would they 
get them in the first place (this type of compiler warning isn't detected in 100% o cases).

In other words - it would be quite easy to mistakenly discard the results of `send`, so a default which guards against 
that (by throwing exceptions) is better, and the "safe" can always be used intentionally version if that's what's 
needed.

### Update 17/04/2024

The `...Safe` operations got renamed to `...OrClosed` or `...OrError`, as they can still throw `InterruptedException`s.
