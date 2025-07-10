# Propagating OpenTelemetry context

Dependency:

```scala
"com.softwaremill.ox" %% "otel-context" % "0.7.1"
```

When using the default OpenTelemetry context-propagation mechanisms, which rely on thread-local storage, the context
will not be propagated across virtual thread boundaries, e.g. when creating new forks as part of 
[`supervised`](../structured-concurrency/fork-join.md) scopes. This might lead to spans not being properly correlated
into traces, or metrics without the appropriate context.

To fix this problem, the context must be propagated whenever a new virtual thread is created. One way to achieve this
is by using a custom thread factory, provided by this module - `PropagatingVirtualThreadFactory`. It can be set 
for the whole app when using [`OxApp`](../utils/oxapp.md), or manually through `oxThreadFactory`:

```scala
import ox.*
import ox.otel.context.PropagatingVirtualThreadFactory

object MyApp extends OxApp:
  override def settings: OxApp.Settings = OxApp.Settings.Default.copy(
    threadFactory = Some(PropagatingVirtualThreadFactory())
  )
  
  def run(args: Vector[String])(using Ox): ExitCode = ExitCode.Success
```