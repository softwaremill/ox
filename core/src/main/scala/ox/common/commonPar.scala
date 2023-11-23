package ox

import java.util.concurrent.Semaphore

private[ox] def commonPar[I, O, C[E] <: Iterable[E], FO](parallelism: Int, iterable: => C[I], transform: I => O, handleOutputs: Seq[O] => FO): FO =
  val s = Semaphore(parallelism)

  supervised {
    val forks = iterable.map { elem =>
      s.acquire()
      fork {
        val o = transform(elem)
        s.release()
        o
      }
    }
    val outputs = forks.toSeq.map(f => f.join())
    handleOutputs(outputs)
  }


