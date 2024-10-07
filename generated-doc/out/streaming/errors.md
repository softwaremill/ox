# Error propagation

Errors are only propagated downstream, ultimately reaching the point where the flow is run / source is discharged. This leads to an exception being thrown there. 

When running flows, any [scopes](../structured-concurrency/fork-join.md) started as part of executing the flow's stages should have completed, before the exception is re-thrown by the `run...` method.

For channel-transforming operations, once the exception reaches the enclosing scope, any forks should become interrupted, including any that are still running and are handling the upstream processing stages.

The approach we decided to take (only propagating errors downstream) is one of the two possible designs - with the other being re-throwing an exception when it's encountered. Please see [the respective ADR](../adr/0001-error-propagation-in-channels.md) for a discussion.
