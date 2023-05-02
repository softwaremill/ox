package ox

import jdk.incubator.concurrent.ScopedValue

import java.util.concurrent.Callable

private def scopedValueWhere[T, U](sv: ScopedValue[T], t: T)(f: => U): U =
  ScopedValue.where(sv, t, (() => f): Callable[U])

class ForkLocal[T](scopedValue: ScopedValue[T], default: T):
  def get(): T = scopedValue.orElse(default)

  def scopedWhere[U](newValue: T)(f: Ox ?=> U): U =
    // the scoped values need to be set inside the thread that's used to create the new scope, but
    // before starting the scope itself, as scoped value bindings can't change after the scope is started
    scopedValueWhere(scopedValue, newValue)(scoped(f))

object ForkLocal:
  def apply[T](initialValue: T): ForkLocal[T] = new ForkLocal(ScopedValue.newInstance[T](), initialValue)
