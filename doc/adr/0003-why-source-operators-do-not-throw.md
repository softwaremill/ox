# 3. Why source operators do not throw

Date: 2024-01-25

## Context

Revisiting ADR #1, what should happen when an error is encountered when processing channel elements? Should it be propagated downstream or re-thrown?

## Decision

In addition to what's mentioned in ADR #1, operators don't throw, but propagate, because we want to allow throw-free coding style. When errors are propagated, on error every daemon operator thread shuts down, and we end the scope gracefully.

Additionally, we assume that data only flows downstream - and this includes errors.
