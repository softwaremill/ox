# HTTP server using Tapir

[Tapir](https://tapir.softwaremill.com) is a library for rapidly developing HTTP APIs. It integrates with a number of 
Scala stacks, including direct-style server interpreters.

The integration is included in the Tapir library and includes:
* exposing regular HTTP endpoints (consuming/producing JSON etc.)
* streaming request & response bodies, converted to/from `Flow`s via `InputStream`s
* web sockets represented as a transformation between incoming & outgoing `Flow`s of web socket frames
* SSE response bodies

For more details refer to the Tapir documentation, specifically:
* the [netty-server-sync interpreter documentation](https://tapir.softwaremill.com/en/latest/server/netty.html)
* [usage examples](https://tapir.softwaremill.com/en/latest/examples.html), tagged with the `Direct` label
* [tutorials](https://tapir.softwaremill.com/en/latest/tutorials/01_hello_world.html), which mostly use the 
  direct-style approach