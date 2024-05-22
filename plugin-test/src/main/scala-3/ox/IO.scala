package ox

// we don't depend on core, so we need to provide a "dummy" IO for tests

trait IO

object IO:
  def unsafe[T](op: IO ?=> T): T = ???
