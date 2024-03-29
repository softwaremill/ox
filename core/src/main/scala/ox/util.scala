package ox

extension [T](inline t: T)
  /** Discard the result of the computation, to avoid discarded non-unit value warnings. Usage: `someMethodCall().another().discard`.
    */
  // noinspection UnitMethodIsParameterless
  inline def discard: Unit =
    val _ = t
    ()

inline def uninterruptible[T](inline f: T): T =
  scoped {
    val t = fork(f)

    def joinDespiteInterrupted: T =
      try t.join()
      catch
        case e: InterruptedException =>
          joinDespiteInterrupted
          throw e

    joinDespiteInterrupted
  }