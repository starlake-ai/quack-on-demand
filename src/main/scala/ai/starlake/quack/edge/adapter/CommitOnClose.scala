package ai.starlake.quack.edge.adapter

/** A result reader whose close COMMITs the write it read ([[StampedCommitArrowReader]]). A caller
  * that gave up on the result (a timeout reported to the client as a failure) disarms it first, so
  * the close only releases: the connection goes without a COMMIT and the write rolls back.
  */
trait CommitOnClose:
  /** No COMMIT from now on, whether at the end of the stream or at close. */
  def disarm(): Unit
