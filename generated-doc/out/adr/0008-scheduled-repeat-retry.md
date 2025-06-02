# 8. Retries

Date: 2024-07-09

## Context

How should the [retries](../scheduling/retries.md) and [repeat](../scheduling/repeat.md) APIs have the common implementation.

## Decision

We're introducing [scheduled](../scheduling/scheduled.md) as a common API for both retries and repeats.

In addition, `Schedule` trait and its implementations are decoupled from the retry DSL, so that they can be used for repeating as well.
`retry` API remains unchanged, but it now uses `scheduled` underneath.

Also, `repeat` functions has been added as a sugar for `scheduled` with DSL focused on repeating.

The main difference between `retry` and `repeat` is about interpretation of the duration provided by the `Schedule` (delay vs interval).
