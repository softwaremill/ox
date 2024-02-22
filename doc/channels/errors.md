# Error propagation

Errors are only propagated downstream, ultimately reaching the point where the source is discharged, leading to an
exception being thrown there.

The approach we decided to take (only propagating errors downstream) is one of the two possible designs -
with the other being re-throwing an exception when it's encountered.
Please see [the respective ADR](../adr/0001-error-propagation-in-channels.md) for a discussion.
