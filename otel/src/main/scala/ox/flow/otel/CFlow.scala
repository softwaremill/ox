package ox.flow.otel

import ox.flow.Flow


//opaque type CFlow[T <: (?, ?)] = Flow[T]
//
//case class Ctx[C, T](c: C, t: T)
//
//trait CtxMaker[C, T]:
//  type C1
//  type T1
//
//  def make(c: C, t: T): Ctx[C1, T1]
//
//object CtxMaker extends LowPrio:
//  type Aux[C, T, CC, TT] = CtxMaker[C, T] {type C1 = CC; type T1 = TT}
//
//  given [C, T, C2]: CtxMaker.Aux[C, Ctx[C2, T], C2, T] = new CtxMaker[C, Ctx[C2, T]] {
//    override type C1 = C2
//    override type T1 = T
//
//    override def make(c: C1, t: Ctx[C2, T]): Ctx[C2, T] = Ctx(t.c, t.t)
//  }
//
//
//trait LowPrio:
//  given [C, T]: CtxMaker.Aux[C, T, C, T] = new CtxMaker[C, T] {
//    override type C1 = C
//    override type T1 = T
//
//    override def make(c: C1, t: T): Ctx[C1, T] = Ctx(c, t)
//  }
//
//trait CtxAccess[C](c: C):
//  def get: C
//
//  extension [T](t: T)
//    def withContext[CC](cc: CC): Ctx[CC, T] = Ctx(cc, t)
//
//  def use[T](f: C => T): T
//
//extension (c: CtxAccess.type)
// def use[T, C, U](f: C => T => U): CtxAccess[C] => T => U = ???
//
//
//trait ContextPush[U]
//
////object ContextPush:
////  given [C, T]: ContextPush[(Ctx[C], T)] = ???
//
//object CtxAccess:
//  def apply[C](c: C) = new CtxAccess[C](c) {}
//
//
//object CFlow:
//  extension [C, T](cflow: CFlow[(C, T)])
//    def value: Flow[(C, T)] = cflow
//    inline def map[U, R](inline f: CtxAccess[C] ?=> T => U)(using cm: CtxMaker[C, U]): CFlow[(cm.C1, cm.T1)] = ???
//
//
//  def fromFlow[CT <: (?, ?)](flow: Flow[CT]): CFlow[CT] = flow
//
//  given t[C, T](using C): (C, T) = ???
//
//trait Foo:
//  def pp: Unit = ???
//
//object T:
//  val x: CFlow[(Foo, Int)] = ???
//  val xx = x.map: ctx ?=> 
//    ctx.use: c => 
//      c.pp
//      tt => tt + 1
