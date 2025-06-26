# Generation of documentation-based Cursor rules

Using:
* the documentation of this library, available at @/generated-doc
* the list of rule names and descriptions, grouped by topics, available at @xyz

generate the content of each Cursor rule.

The rules will guide AI models when using this library in a project. The goal is to capture the main aspects of the library, and NOT to replicate the entire documentation. Each rule should be helpful for an AI coding assistant in generating end-user code, leveraging features of the library.

## What a rule should include

Each rule from the list covers one topic. The content of the rule should be derived from the project's documentation. Rules should be useful in code generation contexts: keep in mind the main goal of providing information useful to AI coding assistants, and specific to the library.

Rules might include code examples, both "good" and "bad" (what to avoid).

## File format and metadata

The rules should be in the .mdc format. 

Rules which are automatically applied, must have the following metadata header:

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
description: [rule description]
globs:
alwaysApply: false
description: 
---

[text of the rule]
```

## Rule files destination and naming

The generated rules should be written to a `cursor-rules` folder, one rule per file. The naming of the rules should follow the `[number]-[rulename].mdc` pattern. The numbering should start with 100.

The rules should be grouped by using nearby numbers for related topics, as defined in the rules listing. The first topic should have numbers 100, 101, 102, etc. The second topic 110, 111, 112 etc.
