package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.branch.BranchNames
import sttp.model.StatusCode

/** A personal access token's scope on one catalog operation the REST surface runs as its caller
  * (undrop, restore, preview, data diff, the Iceberg views): every axis the token carries, checked
  * before anything runs (reviews 3 I1 and 4 N5). The routed executor checks the pools axis and
  * `branchOnly` on the pool it runs on, and nothing checks the databases axis: this does. A
  * session's restriction is unrestricted, so a session always passes.
  */
object TokenScope:

  /** None when `caller` may run it on database `db` (and `pool`, when known); `writes` when it
    * creates or replaces a table (DDL, and a write a branch-only token keeps to a branch pool).
    */
  def refusal(
      caller: ExecCaller,
      db: String,
      pool: Option[PoolKey],
      writes: Boolean
  ): Option[(StatusCode, ErrorResponse)] =
    val r                  = caller.restriction
    def scope(msg: String) = Some((StatusCode.Forbidden, ErrorResponse("token_scope", msg)))
    if !r.allowsDatabase(db) then scope(s"database '$db' is not permitted for this token")
    else if pool.exists(k => !r.allowsPool(k.pool) && !BranchNames.isBranchPool(k.pool)) then
      scope(s"pool '${pool.map(_.pool).getOrElse("")}' is not permitted for this token")
    else if writes && r.verbCeiling.exists(v => !TokenRestriction.covers(v).contains("Ddl")) then
      scope(
        s"this token's verb ceiling (${r.verbCeiling.getOrElse("")}) does not allow creating " +
          "or replacing a table"
      )
    else if writes && r.branchOnly && pool.forall(k => !BranchNames.isBranchPool(k.pool)) then
      Some(
        (
          StatusCode.Forbidden,
          ErrorResponse(
            "write_requires_branch",
            "this token may only write on a branch, and this operation writes"
          )
        )
      )
    else None
