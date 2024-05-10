package ox.channels

import ox.*
import scala.annotation.tailrec

trait SourceTextOps[+T]:
  outer: Source[T] =>

  def lines(using Ox, T <:< Chunk[Byte]): Source[String] =
    // buffer == null is a special state for handling empty chunks in onComplete, in order to tell them apart from empty lines
    outer
      .mapStatefulConcat(() => null: Chunk[Byte])(
        { case (buffer, nextChunk) =>
          @tailrec
          def splitChunksAtNewLine(buf: Chunk[Byte], chunk: Chunk[Byte], acc: Vector[Chunk[Byte]]): (Chunk[Byte], Vector[Chunk[Byte]]) =
            val newlineIdx = chunk.indexWhere(_ == '\n')
            if newlineIdx == -1 then (buf ++ chunk, acc)
            else
              val (chunk1, chunk2) = chunk.splitAt(newlineIdx)
              splitChunksAtNewLine(Chunk.empty, chunk2.drop(1), acc :+ (if buffer != null then buffer ++ chunk1 else chunk1))

          val (newBuffer, toEmit) =
            if nextChunk.length == 0 then (null, Vector.empty)
            else splitChunksAtNewLine(if buffer == null then Empty else buffer, nextChunk, Vector.empty)

          (newBuffer, toEmit)
        },
        onComplete = buf => if buf != null then Some(buf) else None
      )
      .mapAsView(_.asString)
