package ai.starlake.quack.boot

import cats.effect.IO

/** The leader's boot duties (a single manager's, or the HA leader's at boot or on promotion), in
  * their one order:
  *   1. `bootstrap` (DemoBootstrapHook) writes the store directly, so it precedes `restore`;
  *   2. `restore` loads the store into the supervisor's caches;
  *   3. `provision` (ServeProvisionHook) creates through the supervisor, so it follows `restore`;
  *   4. `initDuckLake`, `discover` and `reconcile` then see what `provision` created: a new
  *      database is initialized and a new pool spawned in the same boot;
  *   5. `seedRevoked` reloads the revoked session ids.
  */
object LeaderDuties:

  def apply(
      bootstrap: IO[Unit],
      restore: IO[Unit],
      provision: IO[Unit],
      initDuckLake: IO[Unit],
      discover: IO[Unit],
      reconcile: IO[Unit],
      seedRevoked: IO[Unit]
  ): IO[Unit] =
    bootstrap *> restore *> provision *> initDuckLake *> discover *> reconcile *> seedRevoked
