//package ox.flow.otel
//
//import ox.flow.Flow
//
////object Ctxing extends App:
//

//  given cFromCtx [C](using x:Ctx[C]):C = x.get

//  def usingCtx[C,T,U](using c:CtxAccess[C])(f: C => T => U): T => U = f(c)
//
//
//  val fl:Flow[String ?=> Int] = null
//
//  val cf : CFlow[String ?=> Int]= CFlow.fromFlow(fl)
//  import CFlow.map
//  val cfm:CFlow[String ?=> Int] = cf.map( _ + 1)
//
//
//
//  val cfm2:CFlow[String ?=> List[Int]] = cf.map:
//    usingCtx: ctx =>
//      ctx.charAt(0)
//      List.apply(_)


//package ox.otel
//
//import io.opentelemetry.api.trace.Tracer
//
//object Tarcing {
//  trait TracingStack[T]:
//    def tracer: Tracer
//
//    def elem: T
//
//    def startSpan(): TracingStack[TracingStack[T]]
//
//    def endSpan(): T
//
//  object TracingStack:
//    trait ElemObtain[T]:
//      type Ret
//
//      def obtain(x: T): Ret
//
//    object ElemObtain extends ElemObtainLowPrio:
//      extension[T](t:TracingStack[T])
//        def elemObtain(using ob:ElemObtain[TracingStack[T]]):ob.Ret = ob.obtain(t)
//          
//      given [R,T <: TracingStack[R]](using rec: ElemObtain[T] {type Ret = R}): ElemObtain[TracingStack[T]] with
//        type Ret = R
//
//        override def obtain(x: TracingStack[T]): R = rec.obtain(x.elem)
//    trait ElemObtainLowPrio:
//      given last[T]: ElemObtain[TracingStack[T]] with
//        type Ret = T
//
//        override def obtain(x: TracingStack[T]): T = x.elem 
//
//
//
//    class Root[T](val tracer: Tracer, val elem: T) extends TracingStack[T]:
//      self =>
//      def startSpan(): TracingStack[TracingStack[T]] = new TracingStack[TracingStack[T]] {
//        override def tracer: Tracer = self.tracer
//
//        override def elem: T = self.elem
//      }
//
//      override def endSpan(): T = self.elem
//
//  import ox.otel.Tarcing.TracingStack.ElemObtain.elemObtain
//  @main
//  def m =
//    val root = TracingStack.Root(null, 2)
//    val elem = root.startSpan().startSpan().startSpan().startSpan().endSpan().endSpan().endSpan().endSpan().endSpan()
//}
