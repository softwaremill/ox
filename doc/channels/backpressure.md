# Backpressure

Channels are back-pressured, as the `.send` operation is blocking until there's a receiver thread available, or if
there's enough space in the buffer. The processing space is hence bound by the total size of channel buffers.
