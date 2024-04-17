package ox.channels

import ox.channels.ChannelClosedUnion.{mapUnlessError, orThrow}
import ox.{repeatWhile, supervised}

import scala.collection.mutable

trait SourceDrainOps[+T] { outer: Source[T] =>

  /** Invokes the given function for each received element. Blocks until the channel is done.
    *
    * @throws ChannelClosedException.Error
    *   When there is an upstream error.
    */
  def foreach(f: T => Unit): Unit = foreachOrError(f).orThrow

  /** The "safe" variant of [[foreach]]. */
  def foreachOrError(f: T => Unit): Unit | ChannelClosed.Error =
    var result: Unit | ChannelClosed.Error = ()
    repeatWhile {
      receiveOrClosed() match
        case ChannelClosed.Done     => false
        case e: ChannelClosed.Error => result = e; false
        case t: T @unchecked        => f(t); true
    }
    result

  /** Accumulates all elements received from the channel into a list. Blocks until the channel is done.
    *
    * @throws ChannelClosedException.Error
    *   When there is an upstream error.
    */
  def toList: List[T] = toListOrError.orThrow

  /** The "safe" variant of [[toList]]. */
  def toListOrError: List[T] | ChannelClosed.Error =
    val b = List.newBuilder[T]
    foreachOrError(b += _).mapUnlessError(_ => b.result())

  /** Passes each received element from this channel to the given sink. Blocks until the channel is done. When the channel is done or
    * there's an error, propagates the closed status downstream and returns.
    */
  def pipeTo(sink: Sink[T]): Unit =
    repeatWhile {
      receiveOrClosed() match
        case ChannelClosed.Done     => sink.doneOrClosed(); false
        case e: ChannelClosed.Error => sink.errorOrClosed(e.reason); false
        case t: T @unchecked        => sink.send(t); true
    }

  /** Receives all elements from the channel. Blocks until the channel is done.
    *
    * @throws ChannelClosedException.Error
    *   when there is an upstream error.
    */
  def drain(): Unit = drainOrError().orThrow

  /** The "safe" variant of [[drain]]. */
  def drainOrError(): Unit | ChannelClosed.Error = foreachOrError(_ => ())

  /** Returns the last element from this source wrapped in [[Some]] or [[None]] when this source is empty. Note that `lastOption` is a
    * terminal operation leaving the source in [[ChannelClosed.Done]] state.
    *
    * @return
    *   A `Some(last element)` if source is not empty or `None` otherwise.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].lastOption()  // None
    *     val s = Source.fromValues(1, 2)
    *     s.lastOption()                  // Some(2)
    *     s.receive()                     // ChannelClosed.Done
    *   }
    *   }}}
    */
  def lastOption(): Option[T] = lastOptionOrError().orThrow

  /** The "safe" variant of [[lastOption]]. */
  def lastOptionOrError(): Option[T] | ChannelClosed.Error =
    supervised {
      var value: Option[T] | ChannelClosed.Error = None
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done     => false
          case e: ChannelClosed.Error => value = e; false
          case t: T @unchecked        => value = Some(t); true
      }
      value
    }

  /** Returns the last element from this source or throws [[NoSuchElementException]] when this source is empty. In case when receiving an
    * element fails then [[ChannelClosedException.Error]] exception is thrown. Note that `last` is a terminal operation leaving the source
    * in [[ChannelClosed.Done]] state.
    *
    * @return
    *   A last element if source is not empty or throws otherwise.
    * @throws NoSuchElementException
    *   When this source is empty.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].last()        // throws NoSuchElementException("cannot obtain last element from an empty source")
    *     val s = Source.fromValues(1, 2)
    *     s.last()                        // 2
    *     s.receive()                     // ChannelClosed.Done
    *   }
    *   }}}
    */
  def last(): T = lastOption().getOrElse(throw new NoSuchElementException("cannot obtain last element from an empty source"))

  /** Uses `zero` as the current value and applies function `f` on it and a value received from this source. The returned value is used as
    * the next current value and `f` is applied again with the value received from a source. The operation is repeated until the source is
    * drained.
    *
    * @param zero
    *   An initial value to be used as the first argument to function `f` call.
    * @param f
    *   A binary function (a function that takes two arguments) that is applied to the current value and value received from a source.
    * @return
    *   Combined value retrieved from running function `f` on all source elements in a cumulative manner where result of the previous call
    *   is used as an input value to the next.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @throws exception
    *   When function `f` throws an `exception` then it is propagated up to the caller.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].fold(0)((acc, n) => acc + n)       // 0
    *     Source.fromValues(2, 3).fold(5)((acc, n) => acc - n) // 0
    *   }
    *   }}}
    */
  def fold[U](zero: U)(f: (U, T) => U): U = foldOrError(zero)(f).orThrow

  /** The "safe" variant of [[fold]]. */
  def foldOrError[U](zero: U)(f: (U, T) => U): U | ChannelClosed.Error =
    var current = zero
    var result: U | ChannelClosed.Error = current
    repeatWhile {
      receiveOrClosed() match
        case ChannelClosed.Done     => result = current; false
        case e: ChannelClosed.Error => result = e; false
        case t: T @unchecked        => current = f(current, t); true
    }
    result

  /** Uses the first and the following (if available) elements from this source and applies function `f` on them. The returned value is used
    * as the next current value and `f` is applied again with the value received from this source. The operation is repeated until this
    * source is drained. This is similar operation to [[fold]] but it uses the first source element as `zero`.
    *
    * @param f
    *   A binary function (a function that takes two arguments) that is applied to the current and next values received from this source.
    * @return
    *   Combined value retrieved from running function `f` on all source elements in a cumulative manner where result of the previous call
    *   is used as an input value to the next.
    * @throws NoSuchElementException
    *   When this source is empty.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @throws exception
    *   When function `f` throws an `exception` then it is propagated up to the caller.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].reduce(_ + _)    // throws NoSuchElementException("cannot reduce an empty source")
    *     Source.fromValues(1).reduce(_ + _) // 1
    *     val s = Source.fromValues(1, 2)
    *     s.reduce(_ + _)                    // 3
    *     s.receive()                        // ChannelClosed.Done
    *   }
    *   }}}
    */
  def reduce[U >: T](f: (U, U) => U): U =
    fold(headOption().getOrElse(throw new NoSuchElementException("cannot reduce an empty source")))(f)

  /** Returns the list of up to `n` last elements from this source. Less than `n` elements is returned when this source contains less
    * elements than requested. The [[List.empty]] is returned when `takeLast` is called on an empty source.
    *
    * @param n
    *   Number of elements to be taken from the end of this source. It is expected that `n >= 0`.
    * @return
    *   A list of up to `n` last elements from this source.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].takeLast(5)    // List.empty
    *     Source.fromValues(1).takeLast(0) // List.empty
    *     Source.fromValues(1).takeLast(2) // List(1)
    *     val s = Source.fromValues(1, 2, 3, 4)
    *     s.takeLast(2)                    // List(4, 5)
    *     s.receive()                      // ChannelClosed.Done
    *   }
    *   }}}
    */
  def takeLast(n: Int): List[T] = takeLastOrError(n).orThrow

  /** The "safe" variant of [[takeLast]]. */
  def takeLastOrError(n: Int): List[T] | ChannelClosed.Error =
    require(n >= 0, "n must be >= 0")
    if (n == 0)
      drainOrError()
      List.empty
    else if (n == 1) lastOptionOrError().mapUnlessError(_.toList)
    else
      supervised {
        val buffer: mutable.ListBuffer[T] = mutable.ListBuffer()
        var result: List[T] | ChannelClosed.Error = Nil
        buffer.sizeHint(n)
        repeatWhile {
          receiveOrClosed() match
            case ChannelClosed.Done     => result = buffer.result(); false
            case e: ChannelClosed.Error => result = e; false
            case t: T @unchecked =>
              if (buffer.size == n) buffer.dropInPlace(1)
              buffer.append(t);
              true
        }
        result
      }
}
