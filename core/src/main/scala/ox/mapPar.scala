package ox

import java.util.concurrent.Semaphore
import scala.collection.IterableFactory

def mapPar[I, O, C[E] <: Iterable[E]](parallelism: Int)(iterable: => C[I])(transform: I => O): C[O] =
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
    forks.toSeq.map(f => f.join()).to(iterable.iterableFactory.asInstanceOf[IterableFactory[C]])
  }
