package ox.flow

import ox.Ox

class FlowOps[+T] { outer: Flow[T] =>
  def map[U](f: T => U): Flow[U] = addTransformSinkStage(next => FlowSink.propagateClose(next)(t => next.onNext(f(t))))
  def filter(f: T => Boolean): Flow[T] = addTransformSinkStage(next => FlowSink.propagateClose(next)(t => if f(t) then next.onNext(t)))
  def tap(f: T => Unit): Flow[T] = map(t => { f(t); t })
  def intersperse[U >: T](inject: U): Flow[U] = intersperse(None, inject, None)

  /** Intersperses elements emitted by this flow with `inject` elements. The `start` element is emitted at the beginning; `end` is emitted
    * after the current flow emits the last element.
    *
    * @param start
    *   An element to be emitted at the beginning.
    * @param inject
    *   An element to be injected between the flow elements.
    * @param end
    *   An element to be emitted at the end.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[String].intersperse("[", ", ", "]").toList            // List([, ])
    *     Source.fromValues("foo").intersperse("[", ", ", "]").toList        // List([, foo, ])
    *     Source.fromValues("foo", "bar").intersperse("[", ", ", "]").toList // List([, foo, ", ", bar, ])
    *   }
    *   }}}
    */
  def intersperse[U >: T](start: U, inject: U, end: U): Flow[U] = intersperse(Some(start), inject, Some(end))

  private def intersperse[U >: T](start: Option[U], inject: U, end: Option[U]): Flow[U] =
    Flow(
      new FlowStage:
        override def run(sink: FlowSink[U])(using Ox): Unit =
          start.foreach(sink.onNext)
          last.run(
            new FlowSink[U]:
              private var firstEmitted = false
              override def onNext(t: U): Unit =
                if firstEmitted then sink.onNext(inject)
                sink.onNext(t)
                firstEmitted = true
              override def onDone(): Unit =
                end.foreach(sink.onNext)
                sink.onDone()
              override def onError(e: Throwable): Unit =
                sink.onError(e)
          )
    )

  //

  private inline def addTransformSinkStage[U](inline doTransform: FlowSink[U] => FlowSink[T]): Flow[U] =
    Flow(
      new FlowStage:
        override def run(next: FlowSink[U])(using Ox): Unit =
          last.run(doTransform(next))
    )
}
