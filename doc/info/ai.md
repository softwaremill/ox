# Using Ox with AI coding assistants

AI agents are generally quite good in writing Scala 3 + Ox applications. When the tech stack is explicitly specified (that is, mentions Scala 3, Ox, and other related libraries), when needed agents read Ox's documentation and the source code.

However, sometimes, agents do need more specific guidance. That is especially true when it comes to direct-style, or functional programming in general. For these cases, we've created the [direct-style Scala skill](https://github.com/VirtusLab/scala-skill).

## Context7

[Context7](https://github.com/upstash/context7) is an open-source MCP (Model Context Protocol) server which aims to provide up-to-date documentation for AI coding assistants. You can use the managed, global MCP server, or run your own.

Ox's documentation is [indexed on the global server](https://context7.com/softwaremill/ox).
