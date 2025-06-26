# Generation of a list of documentation-based Cursor rules

Using the documentation of this library, available at @/generated-doc, generate a concise list of Cursor rule names and descriptions. These rules, when written, will guide AI models when using this library in a project. 

**Important**: Only generate the list of rules, not the full content.

The goal is to capture the main aspects of the library, and NOT to replicate the entire documentation. Each rule should be helpful for an AI coding assistant in generating end-user code, leveraging features of the library.

## Scope of the rules

Rules should cover major aspects of the library. Each rule should cover one topic, derived from the documentation. More attention should be given to aspects which are not obvious from the API itself (class and method signatures). 

Rules should cover:
* general usage guidelines
* desired code structuring
* preferred architecture
* main use-cases and major features of the library
* alternative approaches to the status quo, when it comes to the programming platform in question

## What to generate

Produce a list of rule names and descriptions, grouped by topics. More general topics should come before more specific topics. Within each topic, more general rules should come before more specific ones. 

For each rule:
* provide a short name in the kebab-case format.
* indicate whether the rule should be automatically applied to the context (mark it as `(automatically applied)`) or if the agent can request the rule to be included.
* if the rule is agent-requested, provide an agent-friendly description of what the rule covers.

The list should cover the scope as described above, but shouldn’t go into much detail. Keep the list of rules short. Avoid repeating documentation, and keep in mind the main goal, of providing information useful to AI coding assistants, and specific to the library.

## Where to write results

Store the resulting list in the tmp/rules-list.md file.
