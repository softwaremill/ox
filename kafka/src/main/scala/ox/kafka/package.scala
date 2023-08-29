package ox

package object kafka:
  private[kafka] val DefaultBootstrapServers = List("localhost:9092")

  private[kafka] def tapException[T](f: => T)(onException: Throwable => Unit) =
    try f
    catch
      case t: Throwable =>
        try onException(t)
        finally throw t
