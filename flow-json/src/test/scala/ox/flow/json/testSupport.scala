package ox.flow.json

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import ox.Chunk
import ox.flow.Flow

import java.nio.charset.StandardCharsets.UTF_8

case class Person(name: String, age: Int) derives ConfiguredJsonValueCodec

given JsonValueCodec[Int] = JsonCodecMaker.make

def byteFlow(text: String): Flow[Chunk[Byte]] = Flow.fromValues(Chunk.fromArray(text.getBytes(UTF_8)))

def oneByteChunks(text: String): Flow[Chunk[Byte]] =
  Flow.fromIterable(text.getBytes(UTF_8).map(b => Chunk.fromArray(Array(b))).toList)
