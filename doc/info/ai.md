# Using Ox with AI tools

If you are using AI coding assistants or agents, they will only be as useful, as much they know about Ox. Since Ox's documentation (especially the latest features) might not be in the LLMs training set, it might be useful to let the models know about Ox's capabilities and the preferred way that Ox should be used.

Since this is an evolving field, there's no one standard yet, and there are several options to explore. Below you can find a short summary.

## Cursor documentation indexing

If you are using [Cursor](https://www.cursor.com), you might try the [built-in documentation indexing](https://docs.cursor.com/guides/advanced/working-with-documentation) feature. Select `@Docs` -> `Add New Doc` in the editor, or go to `Cursor` -> `Settings` -> `Cursor Settings` -> `Indexing & Docs` -> `Add docs`. In the address field, enter [https://ox.softwaremill.com/latest/](https://ox.softwaremill.com/latest/). After a while, the Ox documentation should be indexed.

Information is scarce on how this actually works, but by analogy with code indexing, this seems to store embeddings of documentation pages on Cursor's servers, which are then used for relevant user queries. Or using AI-terminology, it's a [RAG system](https://cloud.google.com/use-cases/retrieval-augmented-generation?hl=en).

You can then use `@Docs Ox` to hint to Cursor to use Ox's documentation.

## Cursor rules

[Rules](https://docs.cursor.com/context/rules) provide guidance to the LLMs, either by adding the content of the rule as context for each request, by having the models request the content of a rule, or by explicitly mentioning it in the prompt.

Rules might be project-scoped or tied to the user. Project rules are stored in a `.cursor/rules` directory. Ox contains a set of rules, which might guide LLMs when working with Ox-based applications. To include them in your project, simply fetch the current rules into your `.cursor/rules/` directory:

```
git clone --depth=1 --filter=blob:none --sparse https://github.com/softwaremill/ox.git && cd ox && git sparse-checkout set doc && mkdir -p ../.cursor/rules && cp -r doc/* ../.cursor/rules && cd .. && rm -rf ox
```

Some of the rules are automatically applied to the context (to let the model know about basic capabilities), some are agent-requested, which exposes only the rule descriptions to the context. If needed, the agent can fetch the entire rule content, to explore a subject in more depth.

## Context7

[Context7](https://github.com/upstash/context7) is an open-source MCP (Model Context Protocol) server which aims to provide up-to-date documentation for AI coding assistants. You can use the managed, global MCP server, or run your own.

Ox's documentation is indexed in the global server. To reference it in a prompt, be sure to add `use context7`.

## llms.txt

Ox currently does not server a [llms.txt](https://llmstxt.org) file, as it is currently unclear if there are any tools using this. However, if support for this format becomes more widespread, we'll automate generation of this file as well.