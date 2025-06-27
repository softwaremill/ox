# Generation of a list of documentation-based Cursor rules

Using the documentation of this library, available at @/generated-doc, generate a concise list of Cursor rule names and descriptions. 

A Cursor rule guides an agent-based coding assistant when using this library in a project. A rule should provide information that is specific to the given library, and lies outside of the common knowledge about a particular programming ecosystem. The goal is to capture the main aspects of the library, and NOT to replicate the entire documentation.

**Important**: Only generate the list of rules, not the full content.

## Scope of the rules

Rules should cover major aspects of the library:
* main use-cases and features of the library
* the programming style encouraged by the library
* general usage guidelines
* desired code structuring
* preferred architecture

## What to generate

Produce a list of rule names and descriptions. Tha name of the rule should be in the kebab-case format.

The first rule should be marked as `(automatically applied)` and provide an overview of the library, its programming style and main features. It should be an entry point, allowing the agent to request the other rules if necessary.

Subequent rules should be accompanied by an agent-friendly description. The description has to provide information enabling an agent to summon the rule when solving a practical problem. It should be concise, at most 2-3 sentences.

## Guidelines

* avoid going into too much detail
* if needed, include a reference to more detailed documentation available online
* keep the list of rules short
* avoid repeating documentation
* keep in mind the main goal, of providing information useful to AI coding assistants

Each rule should:
* address a single, actionable use-case (apart from the first overview rule)
* cover a single topic
* be derived from the documentation, possibly combining information from multiple pages

## Where to write results

Store the resulting list in the cursor-rules/generation/rules-list.md file.
