package ox.flow.otel

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.*
import io.opentelemetry.context.Context
import ox.flow.Flow
import ox.otel.context.PropagatingVirtualThreadFactory

import scala.compiletime.erasedValue


//class TracedFlow[-Env, +T](env: ForkLocal[Env], last: FlowStage[T]) extends Flow[T](last):
//  override def flatMap[Env1 <: Env, U](f: T => TracedFlow[Env1, U]): TracedFlow[Env1, U] = TracedFlow(env, super.flatMap[U](f).last)
//
//  def tapEnv(f: Env => Unit): TracedFlow[Env, T] = TracedFlow(env, super.tap(_ => f(env.get())).last)
//
//object TracedFlow:
//  def apply[Env, T](env: Env)(flow: Flow[T]) = TracedFlow(ForkLocal.apply(env), flow.last)
//trait StartedSpan[T]:
//  def endSpan(f: Span => Unit): T

//trait OxTelemetry:
//  protected def contextHolder: ContextHolder




trait OxMetrics


//trait TracingOps:
//  self: Tracing =>

//    def traceWith(fun: Flow[T] => Flow[T]): Flow[T] = Flow.usingEmitInline: emit =>
//      fun(flow).runToEmit(emit)


//trait FlowTracingOps extends TracingOps


//    def mapSpanned[U](f: T => U)(startSpan: Tracer => Span, endSpan: Span => Unit): Flow[U] =
//      Flow.usingEmitInline: emit =>
//        flow.runToEmit(FlowEmit.fromInline { t =>
//          val span = startSpan(tracer)
//          emit(f(t))
//          endSpan(span)
//        })


//
//sealed class TracingAccess():
//  def foo = ???
//
//  extension [T](f: T)
//    def prependSpan: T =
//      f
//
////  extension (o:Opened[T])
////    def close(f: Span => Unit) : T
//
//extension [T](flow: Flow[T])
//  def usingCtx[C, U](c: C)(f: C ?=> Flow[T] => Flow[U]): Flow[U] = Flow.usingEmitInline: emit =>
//    f(using c)(flow).runToEmit(emit)
//
//val flow: Flow[Int] = ???

//class FlowTracing(tracing: Tracer):
//  extension [T](flow: Flow[T]) :

//val flowTracing: FlowTracing
//(???)
//given TracingAccess = ???
//val tt = 1.prependSpan
//val x = foo

//
//class traced(val tr: Tracer):
//  def apply[T](usage: traced ?=> T): T = usage(using this)
//
//  extension [T](flow: Flow[T])
//    def traceWith(fun: TracingAccess ?=> Flow[T] => Flow[T]): Flow[T] = Flow.usingEmitInline: emit =>
//      val ta = new TracingAccess()
//      fun(using ta)(flow).runToEmit(emit)
//
//
//def t(using traced) =
//  val f: Flow[Int] = ???
//  f.traceWith()

//  extension [T](extended: Flow[T])
//extension[T](f:Flow[T])
//  def spanInterface(using traced)(usage: SpanInterface[Flow[T]] => Flow[T]):Flow[T] =
//    usage(new SpanInterface[Flow[T]](f) {
//        override def prependWith(f: Tracer => Span): Opened[Flow[Flow[T]]] = ???
//    })
//      extension (o: Opened[T])
//        override def close(f: Span => Unit): T = ???
// }

//  def currentSpan: Span = Span.current()
//  def spanBuilder(name:String) = tracer.spanBuilder(name)


//  extension [T](flow: Flow[T])
//    def spanAccess(usage: SpanAccess[flow.type] ?=> Flow[T]): Flow[T] = usage(using new SpanAccess[flow.type] {
//      extension (t: flow.type)
//        override def prependWith(start: Tracer => Span):Flow[T] =  Flow.usingEmit[T](emit =>
//              val span = start(tracer)
//              flow.runToEmit(emit)
//            )
//    }
//    )
//    def prependSpan(start: Tracer => Span): SpanAccess[flow.type] ?=> StartedSpan[Flow[T]] = (s: SpanAccess[flow.type]) ?=> new StartedSpan:
//      def endSpan(endSpan: Span => Unit): Flow[T] = Flow.usingEmit[T](emit =>
//        val span = start(tracer)
//        flow.runToEmit(emit)
//        endSpan(span)
//      )

//  extension [T](t: T)
//    def startSpan(startSpan:Tracer => Span, endSpan:Span => Unit): T = ???


//object Testing:
//  val tracer2: Tracer = ???
//  val tracer: Tracer = ???
//  val tracedWithMyTracer: Tracing = Tracing(tracer)
//  val f: Flow[Int] = ???
//  val f2 = tracedWithMyTracer:
//    val fff = f.map(x => (x + 1)).traceWith: c1 ?=>
//      flow =>
//        traced(tracer2):
//          flow.traceWith: c ?=>
//            f =>
//              f.prependSpan
//
//    Flow.fromValues(3, 4, 3) ++ fff


//          st.endSpan(_.end())


//  def attachContext[C](contextInit: => C): Flow[C ?=> T] =
//    Flow.usingEmitInline[C ?=> T]: emit =>
//      val f: C ?=> T =
//      flow.runToEmit(FlowEmit.fromInline { t =>
//        val f = emit.apply(using ctx)
//
//
//      })


//
//opaque type ContextFlow[+C, +T] = Flow[(C, T)]
//
//object ContextFlow:
//  def fromFlow[C, T](flow: Flow[(C, T)]): ContextFlow[C, T] = flow
//
//  extension [C, T](c: ContextFlow[C, T])
//    def toFlow: Flow[(C, T)] = c
//
//    def map[U](f: T => U): ContextFlow[C, U] = fromFlow(toFlow.map((c, x) => (c, f(x))))
//
//    def filter(f: T => Boolean): ContextFlow[C, T] = fromFlow(toFlow.filter((c, x) => f(x)))
//
//    def mapContext[C1](f: C => C1): ContextFlow[C1, T] = fromFlow(toFlow.map((c, x) => (f(c), x)))
//
//    def tapContext(f: C => Unit): ContextFlow[C, T] = fromFlow(toFlow.tap(x => f(x._1)))
//
//    /** Todo - this is questionable, because we are using Flow in mapping fun, yet scala for-comprehension is working*/
//    def flatMap[B](f: T => Flow[B]): ContextFlow[C, B] = fromFlow(toFlow.flatMap((c, t) => f(t).map(c -> _)))
//
//
//extension [T](flow: Flow[T])
//  def withContext[C](context: C): ContextFlow[C, T] = ContextFlow.fromFlow(flow.map(context -> _))
//  def extractContext[C](f: T => C): ContextFlow[C, T] = ContextFlow.fromFlow(flow.map(x => f(x) -> x))
//
//
//object Usage:
//  val flow: Flow[Int] = Flow.fromValues(1, 2, 3)
//  val tracer: Tracer = ???
//  val meter:Meter = ???
//  val flowWithContext: ContextFlow[Tracer, Int] = flow.withContext(tracer)
//  val withStartedSpan: ContextFlow[(Tracer, Span), Int] = flowWithContext.mapContext(t => (t, t.spanBuilder("").startSpan()))
//  val operatingOnElements: ContextFlow[(Tracer, Span), Int] = withStartedSpan.map(_ + 1)
//
//  val endedSpan: ContextFlow[Tracer, Int] = operatingOnElements.mapContext { (t, s) => s.end(); t }
//
//  val moreSpans: ContextFlow[(Tracer, List[Span]), Int] = withStartedSpan.mapContext { (t, s) => (t, t.spanBuilder("newSpan").startSpan() :: s :: Nil) }
//
//  val flow2: Flow[String] = Flow.fromValues("one", "two")
//
//  val questionable: ContextFlow[(Tracer, List[Span]), Int] = for {
//    s <- moreSpans
//    s2 <- flow
//    s3 <- flow2
//  } yield s + s3.length
//
//  val backToFlow: Flow[((Tracer, List[Span]), Int)] = moreSpans.toFlow
//
//  val withMeter: ContextFlow[(Meter, Tracer, List[Span]), Int] = moreSpans.mapContext((t, s) => (meter, t, s))
//
//  val withCounter: ContextFlow[(Meter, Tracer, List[Span], LongCounter), Int] = withMeter.mapContext((m, t, s) => (m, t, s, m.counterBuilder("s").build()))
//
//  val counted: ContextFlow[(Meter, Tracer, List[Span], LongCounter), Int] = withCounter.tapContext(_._4.add(1))
//
//
//  val sink = Channel.rendezvous[ObservableLongMeasurement]
//
//  val subscribed: ObservableLongCounter = meter.counterBuilder("i will subscribe to this").buildWithCallback(sink.send)
//
//  val flowConected: Flow[ObservableLongMeasurement] = Flow.usingEmit[ObservableLongMeasurement]: emit =>
//    FlowEmit.channelToEmit(sink, emit)
//
//
//
//


//
//type MeteredFlow[T] = ContextFlow[Meter, T]
////type TracedFlow[S<:TracingStack[S],T] = ContextFlow[TracingStack[S], T]
//
//opaque type TracedF[T] = Tracer => Flow[T]
//opaque type MeteredF[T] = Meter => Flow[T]
//
//class EnvFlow[-E, +T](val wrapped: E ?=> Flow[T])
//
//object EnvFlow:
//  def apply[E, T](f: E ?=> Flow[T]): EnvFlow[E, T] = new EnvFlow(f)
//
//val x: EnvFlow[Tracer ?=> Meter, Int] = ???
//
////extension [G <: ContextFunction1[?, ?]](tupled: G)
////  def untuple[F](using tf: TupledFunction[F, G]): F = tf.untupled(tupled)
//
//extension [A, T](f: ContextFunction1[A, T])
//  def toFun: (A) => T = (a: A) => f(using a)
//
//
//extension [E, T](ef: EnvFlow[E, T])
//  def map[U](f: T => U): EnvFlow[E, U] = {
//    EnvFlow(E ?=> ef.wrapped.map(f))
//  def requireEnv[Env]: EnvFlow[(Env, E), T] =
//    type CT = ContextFunction1[ContextFunction1[E, Env], T]

//    val inner: ContextFunction1[Env, E ?=> Flow[T]] = (e1: Env) ?=> (e: E) ?=> ef.wrapped(using e)
//    val innerrr = toFun(inner)
//    val inner2: ContextFunction2[Env, E, Flow[T]] = inner
//    val inner2Tupled: ContextFunction1[(Env, E), Flow[T]] = (t: (Env, E)) ?=> inner2(using t._1, t._2)
//    val inner2UnTupled: ContextFunction1[Env, E ?=> Flow[T]] = (e: Env) ?=> (e1: E) ?=> inner2Tupled(using (e, e1))
////    val inner2UnTupledGen = untuple(inner2Tupled)
//    val inner2UnTupled2: ContextFunction2[Env, E, Flow[T]] = inner2UnTupled

//    val inner3: ContextFunction1[Env ?=> E, Flow[T]] = (e1: Env) ?=> (e2: E) ?=> inner2(using e1, e2)
//
//    ???
//  }
//
//
//case class Enved[EnvedT](e: EnvedT)
//
//trait LeafMapper[From, A]:
//  type To[_]
//
//  def map[B](from: From)(f: A => B): To[B]
//
//object LeafMapper extends LowPrio:
//  type Aux[From, A, ToT[_]] = LeafMapper[From, A] {type To[t] = ToT[t]}
//
//  def map[From, A, B](from: From)(f: A => B)(using m: LeafMapper[From, A]): m.To[B] = m.map(from)(f)
//  //  extension [From,A](from:From)
//  //    def map[B](f:A => B)(using m:LeafMapper.Aux[From,A,B]):m.To[B] = m.map(from)(f)
//
//  given forEnved[From <: ContextFunction1[?, ?], A, ToT[_]](using delegate: LeafMapper.Aux[From, A, ToT]): LeafMapper[Enved[From], A] with
//    type To[b] = Enved[ToT[b]]
//
//    override def map[B](from: Enved[From])(f: A => B): To[B] = Enved(delegate.map(from.e)(f))
//
//
//  given rec[Env, Inner, A, ToT[_]](using rec: LeafMapper.Aux[Inner, A, ToT]): LeafMapper[ContextFunction1[Env, Inner], A] with
//
//    override type To[b] = Env ?=> ToT[b]
//
//    override def map[B](from: Env ?=> Inner)(f: A => B): To[B] = (env: Env) ?=> rec.map(from)(f)
//
//trait LowPrio:
//  given last[Env, A](using NotGiven[A <:< ContextFunction1[?, ?]]): LeafMapper[ContextFunction1[Env, A], A] with
//
//    override type To[b] = Env ?=> b
//
//    override def map[B](from: Env ?=> A)(f: A => B): To[B] = Env ?=> f(from)
//
//import ox.otel.LeafMapper.map
//
//val mapped: Int ?=> String ?=> Int = LeafMapper.map((i: Int) ?=> (s: String) ?=> List(2, 3, 4))(_.size)
//val e: Enved[Int ?=> String ?=> List[Int]] = ???
//val res = LeafMapper.map(e)(_.size)
//
////
////  override def map[B](f: Elem => B): Ret =
//
//trait EnvWrapper[Repr, T, LiftedRepr[+_]]:
//  type LiftedReprT[+t] = LiftedRepr[t]
//
//  def wrap(x: Repr): Enved[T, LiftedRepr]
//
//object EnvWrapper extends LowPrio:
//  given rec[A, B, IT, IW[+_]](using inner: EnvWrapper[B, IT, IW]): EnvWrapper[ContextFunction1[A, B], IT, [t] =>> A ?=> IW[t]] with
//    self =>
//    override def wrap(x: A ?=> B): Enved[IT, this.LiftedReprT] = new Enved[IT, self.LiftedReprT]:
//      override def unwrap: self.LiftedReprT[IT] = A ?=> inner.wrap(x).unwrap
//
//trait LowPrio:
//  inline given last[A, B](using NotGiven[B <:< ContextFunction1[?, ?]]): EnvWrapper[ContextFunction1[A, B],B,[t] =>> A ?=> t] with
//    self =>
//    override def wrap(x: A ?=> B): Enved[B, self.LiftedReprT] = new Enved[B, self.LiftedReprT]:
//      override def unwrap: self.LiftedReprT[B] = A ?=> x
//
////trait EnvUnwrapper[From, To]:
////  def unwrap(x: From): To
////
////object EnvUnwrapper extends LowPrio:
////  given rec[A, B, IT, IW[+_]](using inner: EnvUnwrapper[B, IT, IW]): EnvUnwrapper[ContextFunction1[A, B], IT, [t] =>> A ?=> IW[t]] with
////    self =>
////    override def unwrap(x: A ?=> B): Enved[IT, this.LiftedReprT] = new Enved[IT, self.LiftedReprT]:
////      override def unwrap: self.LiftedReprT[IT] = A ?=> inner.wrap(x).unwrap
////
////trait LowPrio:
////  inline given last[A, B](using NotGiven[B <:< ContextFunction1[?, ?]]): EnvUnwrapper[ContextFunction1[A, B],B,[t] =>> A ?=> t] with
////    self =>
////    override def unwrap(x: A ?=> B): Enved[B, self.LiftedReprT] = new Enved[B, self.LiftedReprT]:
////      override def unwrap: self.LiftedReprT[B] = A ?=> x
//
//
//object Enved:
//  def wrap[A, B, T, W[+_]](f: ContextFunction1[A, B])(using e: EnvWrapper[ContextFunction1[A, B], T, W]): Enved[T, W] = e.wrap(f)
//
//  extension [T](t: T)
//    def requireEnv[Env]: Enved[T, [t] =>> Env ?=> t] = new Enved:
//      override def unwrap: Env ?=> T = (e: Env) ?=> t
//
//  extension [T, WO[+_] <: ContextFunction1[?, ?]](tt: Enved[T, WO])
//    def requireEnv[Env]: Enved[T, [t] =>> Env ?=> WO[t]] = new Enved[T, [t] =>> Env ?=> WO[t]]:
//      override def unwrap: Env ?=> WO[T] = (e: Env) ?=> tt.unwrap
//
////  extension [T,Repr[+_]](enved:Enved[T,Repr])
////    def useEnv[Env,U](f: Env ?=> T ?=> U):U =
//
////  extension [T, W[+_]](enved: Enved[T, W])
////    def map[U](t: T => U)(using Unwrap) =
////      enved.unwrap
//
//
//trait EnvProvider[Env, T]:
//  type Ret
//
//  def provide(env: Env, enved: T): Ret
//
////[t] =>> ContextFunction1[Env,t]]
//object EnvProvider:
//  given last[T, Env, W[+t] <: ContextFunction1[Env, t]]: EnvProvider[Env, Enved[T, W]] with {
//    type Ret = T
//
//    override def provide(env: Env, enved: Enved[T, W]): T =
//      enved.unwrap(using env)
//  }
//
//  extension [T, WO[+_] <: ContextFunction1[?, ?]](tt: Enved[T, WO])
//    def provide[Env](env: Env)(using provider: EnvProvider[Env, Enved[T, WO]]): provider.Ret = provider.provide(env, tt)
//
//
//import ox.otel.Enved.requireEnv
//
////val r = 1.enved
//val ss: String ?=> Tracer ?=> Int = 1.requireEnv[Tracer].unwrap
//val wrp: Enved[String, [T] =>> Int ?=> String ?=> T] = Enved.wrap((x: Int) ?=> (y: String) ?=> "")
////wrp.using:
//
//
//def fun[T,C](enved:Enved[T,C]) = ???
//
////def insideOut[A, B, C](f: A => B => C): B => A => C = (b: B) => (a: A) => f(a)(b)
//
////val e2 = x.map(_ + 1)
//
////given ttt:Tracer = ???
////given iii:Meter = ???
////val r: Flow[Int] = e2.wrapped
////
////val sss: Flow[Int] = ???
//
//
////
//
////trait SpanStack[Depth<:Int]:
////  def pop:Span
////
////class SpanStack
////
////extension (last: SpanStack[1])
////  def end(): = last.pop.end()
////
////
//
//
////    override def map[U](f: T => U): TracingStack[U] = ???
//
//
////extension [T<:TracingStack[T]](stack:TracingStack[T])
////  def elem:T = stack.elem
////extension [T](stack:TracingStack[T])
////  def elem:T = stack.elem
//
//
////  def elem:T
//
//
////sealed trait TracingStack[T <: TracingStack[T]]:
////  def tracer: Tracer
////  protected def scope:Scope
//
//
////object TracingStack:
////  private final class Root(val tracer: Tracer) extends TracingStack[Nothing]:
////    override protected def scope: Scope = ???
//
//
////, T
////]
//////
////
//
//

//
//////extension (span: SpanBuilder)
//////  def start(): Span =
////    println("starting")
//    span.startSpan()
//
//extension [T](flow: Flow[(Tracer, T)])
//  def startSpan(name: String): Flow[(Span, T)] = flow.map { (tracer, elem) =>
//    tracer.spanBuilder(name).startSpan() -> elem
//  }
//  def map[U](f: T => U): Flow[(Tracer, U)] = flow.map((t, x) => (t, (f(x))))


