# Stability of modules

The modules are categorized using the following levels:

* **stable**: binary compatibility is guaranteed within a major version; adheres to semantic versioning
* **stabilizing**: the API is mostly stable, with rare binary-incompatible changes possible in minor releases (only if necessary)
* **experimental**: API can change significantly even in patch releases

The major version is increased when there are binary-incompatible changes in **stable** modules.

The minor version is increased when there are significant new features in **stable** modules (keeping compatibility), or binary-incompatible changes in **stabilizing** modules.

The patch version is increased when there are binary-compatible changes in **stable** / **stabilizing** modules, any changes in **exeperimental** modules, or when a new module is added (e.g. a new integration).

## Main modules 

| Module                | Level        |
|-----------------------|--------------|
| core                  | stable       |
| flow-reactive-streams | stabilizing  |
| kafka                 | stabilizing  |
| mdc-logback           | stabilizing  |
| cron                  | stabilizing  |
| otel-context          | stabilizing  |
