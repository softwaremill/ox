package ox.characterization

@main def nestedContexts(): Unit =
  case class Ctx(n: Int)
  var counter = 0

  def ctx(f: Ctx ?=> Unit): Unit =
    counter += 1
    f(using Ctx(counter))

  ctx {
    println(summon[Ctx])
    ctx {
      println(summon[Ctx])
      ctx {
        println(summon[Ctx])
      }
    }
  }
end nestedContexts
