# Error propagation

Errors are only propagated downstream, ultimately reaching the point where the source is discharged, typically leading 
to an exception being thrown there. This, in turn, should end the enclosing [scope](../structured-concurrency/fork-join.md), and cancel any
other forks, that are still running and handling the upstream processing stages.

The approach we decided to take (only propagating errors downstream) is one of the two possible designs -
with the other being re-throwing an exception when it's encountered.
Please see [the respective ADR](../adr/0001-error-propagation-in-channels.md) for a discussion.
