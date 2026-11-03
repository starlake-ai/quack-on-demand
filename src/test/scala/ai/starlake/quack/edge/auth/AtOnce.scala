package ai.starlake.quack.edge.auth

import org.scalatest.Assertions

import java.util.concurrent.atomic.AtomicReference

/** Runs a call that must answer at once, on a thread of its own, and waits for it a few seconds
  * only. The cases that take every permit of a bound on exchanges ask for this: a call that waited
  * for a permit (`Semaphore.acquire` where `tryAcquire` belongs) would be parked for good, and the
  * suite with it, where here it is a failed case that says what the call did. The parked thread is
  * interrupted, which `acquire` answers by giving up without taking a permit, so the failing case
  * leaves no thread behind and the permits of its caller keep their count.
  */
object AtOnce:

  /** How long a call may take to answer. One with no permit to take answers in microseconds. */
  val WaitMs: Long = 5000L

  def apply[A](call: => A): A = within(WaitMs)(call)

  /** The answer of `call`, what it threw, or a failure when it had not answered within `waitMs`. */
  def within[A](waitMs: Long)(call: => A): A =
    val ended  = new AtomicReference[Either[Throwable, A]]()
    val caller = new Thread(() =>
      ended.set(
        try Right(call)
        catch case e: Throwable => Left(e)
      )
    )
    caller.setDaemon(true)
    caller.start()
    caller.join(waitMs)
    if caller.isAlive then
      caller.interrupt()
      caller.join(waitMs)
      Assertions.fail(
        s"the call had not answered after $waitMs ms: it waited where it had to answer at once"
      )
    else
      ended.get match
        case Right(answer) => answer
        case Left(error)   => throw error
