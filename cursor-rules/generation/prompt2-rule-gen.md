# Generation of documentation-based Cursor rules

Using:

* the documentation of this library, available at @/generated-doc
* the list of rule names and descriptions, available at @rules-list.md

generate the content of each Cursor rule.

A Cursor rule guides an agent-based coding assistant when using this library in a project. A rule should provide information that is specific to the given library, and lies outside of the common knowledge about a particular programming ecosystem. The goal is to capture the main aspects of the library, and NOT to replicate the entire documentation.

## File format and metadata

The rules should be in the .mdc format. 

The first overview rule should be automatically applied and must have the following metadata header:

```
---
description:
globs: 
alwaysApply: true
---

[text of the rule]
```

Other rules will be provided to agents so that they can request them when needed. They **must include the rule description**, as given in the list of rules:

```
---
globs:
alwaysApply: false
description: [rule description]
---

[text of the rule]
```

## Guidelines

* avoid going into too much detail
* if needed, include a reference to more detailed documentation available online
* avoid repeating documentation
* keep in mind the main goal, of providing information useful to AI coding assistants

Rules might include code examples, both "good" and "bad" (what to avoid).

## Rule files destination and naming

The generated rules should be written to a `cursor-rules` folder, one rule per file. The naming of the rules should follow the `[number]-[rulename].mdc` pattern. The numbering should start with 100.

The rules should be grouped by using nearby numbers for related topics, as defined in the rules listing. The first topic should have numbers 100, 101, 102, etc. The second topic 110, 111, 112 etc.
