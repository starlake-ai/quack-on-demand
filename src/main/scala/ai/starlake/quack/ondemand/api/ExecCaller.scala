package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction

/** Who is running a statement through the routed executor.
  *
  * This is a value type rather than three loose parameters so that adding a call site forces an
  * explicit decision about the restriction. The alternative considered was pinning an already
  * attenuated EffectiveSet on a connection context, which needs no signature change but makes the
  * security property rest on remembering to attenuate first. Here, omitting it does not compile.
  *
  * `superuserAdmissible` is required for the same reason. It is the caller's realm, handed to the
  * handshake gate as `AuthzRequest.superuserAdmissible`: true only for a caller that is itself a
  * superuser, a superuser session (`SessionScope.superuser`, see [[RestCaller]]) or a PAT whose
  * owning row is tenant-less ([[RestCaller]], `McpDataTools.callerFor`). Every other caller binds a
  * row of the pool's own tenant, never a superuser row: the rule the FlightSQL handshake applies to
  * a tenant-realm credential. The flag grants nothing by itself: it is not a privilege signal (see
  * `system` below), it only lets the gate bind the superuser row the caller's own identity names.
  *
  * `patId` is set for a PAT bearer by [[RestCaller]] (the catalog preview, data-diff, undrop and
  * restore REST endpoints) and by `McpDataTools.callerFor` (MCP). The routed executor
  * (`Main.routedExecutor`) threads it into `FlightSqlRouter.execute`, so the statement-history and
  * audit rows and the `ActiveStatementRegistry` entry carry it: that is what lets a revoke of the
  * token kill the statement. System and session callers carry `None`.
  *
  * `system` is the ONLY privilege signal: a system caller gets the synthetic superuser EffectiveSet
  * in the routed executor (no ACL, no CLS/RLS, never attenuated), and `superuserAdmissible` is no
  * route to it. It is set only by [[ExecCaller.system]] at trusted internal sites (the static key,
  * the restore dry run, the branch change counter). `identity` is a label for logs and history;
  * user names are not reserved (a tenant may have a user called "superuser"), so nothing may decide
  * privilege from it.
  */
final case class ExecCaller(
    connectionId: String,
    identity: String,
    restriction: TokenRestriction,
    superuserAdmissible: Boolean,
    patId: Option[String] = None,
    system: Boolean = false
):
  /** The row cap actually applied: the server cap, the token's cap and the request's, smallest
    * wins. A token can lower the cap and can never raise it.
    */
  def effectiveMaxRows(serverCap: Int, requested: Int): Int =
    (List(serverCap, requested) ++ restriction.maxRows.toList).min.max(1)

object ExecCaller:

  /** A trusted internal caller: the static key or a system leg. Labelled with
    * [[CatalogPreviewHandlers.SuperuserIdentity]] for logs; the privilege is the flag. It never
    * reaches the handshake gate, so its `superuserAdmissible = true` decides nothing.
    */
  def system(connectionId: String): ExecCaller =
    ExecCaller(
      connectionId,
      CatalogPreviewHandlers.SuperuserIdentity,
      TokenRestriction.Unrestricted,
      superuserAdmissible = true,
      system = true
    )

  /** A caller with no token restriction: a login session. `superuserAdmissible` is the session's
    * own `SessionScope.superuser`.
    */
  def unrestricted(
      connectionId: String,
      identity: String,
      superuserAdmissible: Boolean
  ): ExecCaller =
    ExecCaller(
      connectionId,
      identity,
      TokenRestriction.Unrestricted,
      superuserAdmissible = superuserAdmissible
    )
