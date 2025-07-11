# HTTP client using sttp

[sttp-client](https://sttp.softwaremill.com) is a Scala HTTP client integrating with a number of Scala stacks, 
including direct-style backends.

The integration is included in the sttp-client library and includes:
* synchronous backends, which are designed to work with direct-style Scala
* streaming request & response bodies, converted to/from `Flow`s via `InputStream`s
* interacting with WebSockets, either using the `WebSocket` interface directly, or using `Flow`s of web socket frames
* receiving SSE streams as `Flow[ServerSentEvent]`

For more details refer to the sttp-client documentation, specifically:
* [synchronous backends documentation](https://sttp.softwaremill.com/en/latest/backends/synchronous.html)
* [usage examples](https://sttp.softwaremill.com/en/latest/examples.html), tagged with the `Direct` label