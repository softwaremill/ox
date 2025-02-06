package ox

import ox.channels.ActorRef

/** Returns a concurrency-scope-specific runner, which allows scheduling of functions to be run within the current concurrency scope, from
  * the context of arbitrary threads (not necessarily threads that are part of the current concurrency scope).
  *
  * Usage: obtain a runner from within a concurrency scope, while on a fork/thread that is managed by the concurrency scope. Then, pass that
  * runner to the external library. It can then schedule functions (e.g. create forks) to be run within the concurrency scope from arbitary
  * threads, as long as the concurrency scope isn't complete.
  *
  * Execution is scheduled through an [[Actor]], which is lazily created, and bound to an [[Ox]] instances.
  *
  * This method should **only** be used when integrating Ox with libraries that manage concurrency on their own, and which run callbacks on
  * a managed thread pool. The logic executed by the third-party library should be entirely contained within the lifetime of this
  * concurrency scope. The sole purpose of this method is to enable running scope-aware logic from threads **other** than Ox-managed.
  *
  * Use with care!
  *
  * @see
  *   [[ExternalRunner.runAsync]]
  */
def externalRunner()(using Ox): ExternalRunner = ExternalRunner(summon[Ox].externalSchedulerActor)

class ExternalRunner(scheduler: ActorRef[ExternalScheduler]):
  /** Runs the given function asynchronously, in the scope of the [[Ox]] concurrency scope in which this runner was created.
    *
    * `f` should return promptly, not to obstruct execution of other scheduled functions. Typically, it should start a background fork.
    */
  def runAsync(f: Ox ?=> Unit): Unit = scheduler.ask(_.run(f))
end ExternalRunner
