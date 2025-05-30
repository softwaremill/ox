package ox

import ox.channels.ActorRef

/** Returns a concurrency-scope-specific runner, which allows scheduling of functions to be run within the current concurrency scope, from
  * the context of arbitrary threads (not necessarily threads that are part of the current concurrency scope).
  *
  * Usage: obtain a runner from within a concurrency scope, while on a fork/thread that is managed by the concurrency scope. Then, pass that
  * runner to the external library. It can then schedule functions (e.g. create forks) to be run within the concurrency scope from arbitrary
  * threads, as long as the concurrency scope isn't complete.
  *
  * Execution is scheduled through an [[Actor]], which is lazily created, and bound to an [[Ox]] instances. The functions are run serially,
  * hence they should not block. Any exceptions thrown by the functions will cause the entire concurrency scope to end.
  *
  * This method should **only** be used when integrating Ox with libraries that manage concurrency on their own, and which run callbacks on
  * a managed thread pool. The logic executed by the third-party library should be entirely contained within the lifetime of this
  * concurrency scope. The sole purpose of this method is to enable running scope-aware logic from threads **other** than Ox-managed.
  *
  * Use with care!
  *
  * @see
  *   [[InScopeRunner.async]]
  */
def inScopeRunner()(using Ox): InScopeRunner = InScopeRunner(summon[Ox].runInScopeActor)

/** @see
  *   inScopeRunner
  */
class InScopeRunner(runInScope: ActorRef[RunInScope]):
  /** Runs the given function asynchronously, in the scope of the [[Ox]] concurrency scope in which this runner was created.
    *
    * `f` should not block and return promptly, not to obstruct execution of other scheduled functions. Typically, it should start a
    * background fork. Any exceptions thrown by `f` will be cause the entire scope to end.
    */
  def async(f: Ox ?=> Unit): Unit = runInScope.tell(_.apply(f))
end InScopeRunner
