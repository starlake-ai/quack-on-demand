package ai.starlake.acl.policy

import ai.starlake.acl.model.{Grant, GrantTarget, Principal, TableRef, UserIdentity}

import java.time.Instant

/** Result of checking access for a specific table and user. */
enum GrantMatchResult:
  case Matched(authorized: Boolean)
  case Expired(expiredAt: Instant)
  case NoMatch

/** Internal representation of a grant entry with its authorized flag. */
private[policy] final case class GrantEntry(principal: Principal, authorized: Boolean)

/** Internal representation of an expired grant entry for audit trail. */
private[policy] final case class ExpiredEntry(principal: Principal, expiredAt: Instant)

final class GrantIndex private (
    private val byDatabase: Map[String, Set[GrantEntry]],
    private val bySchema: Map[String, Set[GrantEntry]],
    private val byTable: Map[String, Set[GrantEntry]],
    private val expiredByDatabase: Map[String, Set[ExpiredEntry]],
    private val expiredBySchema: Map[String, Set[ExpiredEntry]],
    private val expiredByTable: Map[String, Set[ExpiredEntry]]
):

  /** Check whether the given user has access to the given table and whether the grant is authorized.
    *
    * Checks three hierarchy levels with short-circuit: table -> schema -> database.
    * Only table-level grants can return authorized=true. Schema and database grants are always
    * transparent (authorized=false) regardless of the grant's authorized flag.
    *
    * If no active grant matches but an expired grant exists for this user/table,
    * returns Expired instead of NoMatch for audit trail purposes.
    */
  def checkAccess(table: TableRef, user: UserIdentity): GrantMatchResult =
    val principals: Set[Principal] =
      Set(Principal.user(user.name)) ++ user.groups.map(Principal.group)

    def matchEntries(entries: Option[Set[GrantEntry]]): Option[Set[GrantEntry]] =
      entries.map(_.filter(e => principals.contains(e.principal))).filter(_.nonEmpty)

    def matchExpired(entries: Option[Set[ExpiredEntry]]): Option[Instant] =
      entries
        .map(_.filter(e => principals.contains(e.principal)))
        .filter(_.nonEmpty)
        .map(_.map(_.expiredAt).max)

    // Table-level: authorized flag is meaningful
    matchEntries(byTable.get(table.canonical)) match
      case Some(matched) =>
        GrantMatchResult.Matched(authorized = matched.exists(_.authorized))
      case None =>
        // Schema-level: always transparent
        matchEntries(bySchema.get(s"${table.database}.${table.schema}")) match
          case Some(_) =>
            GrantMatchResult.Matched(authorized = false)
          case None =>
            // Database-level: always transparent
            matchEntries(byDatabase.get(table.database)) match
              case Some(_) =>
                GrantMatchResult.Matched(authorized = false)
              case None =>
                // Check expired grants for audit trail
                val expiredAt = List(
                  matchExpired(expiredByTable.get(table.canonical)),
                  matchExpired(expiredBySchema.get(s"${table.database}.${table.schema}")),
                  matchExpired(expiredByDatabase.get(table.database))
                ).flatten.maxOption
                expiredAt match
                  case Some(exp) => GrantMatchResult.Expired(exp)
                  case None      => GrantMatchResult.NoMatch

  /** Check whether ANY grant covers the given table, regardless of principal.
    *
    * Returns true if any active (non-expired) grant targets this table at
    * the table, schema, or database level. Used by DefaultAllow mode to
    * distinguish "not mentioned at all" from "mentioned but user not authorized".
    */
  def hasAnyGrant(table: TableRef): Boolean =
    byTable.contains(table.canonical) ||
      bySchema.contains(s"${table.database}.${table.schema}") ||
      byDatabase.contains(table.database)

  /** Check whether the given user is authorized to access the given table.
    *
    * Backward-compatible convenience method that delegates to checkAccess.
    * Returns `true` on any match (regardless of authorized flag).
    */
  def isAuthorized(table: TableRef, user: UserIdentity): Boolean =
    checkAccess(table, user) match
      case GrantMatchResult.Matched(_) => true
      case GrantMatchResult.Expired(_) => false
      case GrantMatchResult.NoMatch    => false

object GrantIndex:

  /** Build a GrantIndex from a list of grants, indexing by target hierarchy level.
    *
    * Grants whose `expires` instant is before `now` are placed in expired maps
    * (for audit trail) rather than the active index. Grants without `expires` never expire.
    *
    * GrantTarget.All is ignored (reserved for future programmatic use). All map keys are already
    * lowercase because GrantTarget factories normalize on construction.
    */
  def build(grants: List[Grant], now: Instant = Instant.now()): GrantIndex =
    var dbMap: Map[String, Set[GrantEntry]]     = Map.empty
    var schemaMap: Map[String, Set[GrantEntry]]  = Map.empty
    var tableMap: Map[String, Set[GrantEntry]]   = Map.empty
    var expDbMap: Map[String, Set[ExpiredEntry]]     = Map.empty
    var expSchemaMap: Map[String, Set[ExpiredEntry]]  = Map.empty
    var expTableMap: Map[String, Set[ExpiredEntry]]   = Map.empty

    grants.foreach { grant =>
      val isExpired = grant.expires.exists(_.isBefore(now))

      if isExpired then
        val expiredAt = grant.expires.get
        val entries = grant.principals.map(p => ExpiredEntry(p, expiredAt)).toSet
        grant.target match
          case GrantTarget.Database(database) =>
            expDbMap = expDbMap.updatedWith(database) {
              case Some(existing) => Some(existing ++ entries)
              case None           => Some(entries)
            }
          case GrantTarget.Schema(database, schema) =>
            val key = s"$database.$schema"
            expSchemaMap = expSchemaMap.updatedWith(key) {
              case Some(existing) => Some(existing ++ entries)
              case None           => Some(entries)
            }
          case GrantTarget.Table(database, schema, table) =>
            val key = s"$database.$schema.$table"
            expTableMap = expTableMap.updatedWith(key) {
              case Some(existing) => Some(existing ++ entries)
              case None           => Some(entries)
            }
          case GrantTarget.All => ()
      else
        val entries = grant.principals.map(p => GrantEntry(p, grant.authorized)).toSet
        grant.target match
          case GrantTarget.Database(database) =>
            dbMap = dbMap.updatedWith(database) {
              case Some(existing) => Some(existing ++ entries)
              case None           => Some(entries)
            }
          case GrantTarget.Schema(database, schema) =>
            val key = s"$database.$schema"
            schemaMap = schemaMap.updatedWith(key) {
              case Some(existing) => Some(existing ++ entries)
              case None           => Some(entries)
            }
          case GrantTarget.Table(database, schema, table) =>
            val key = s"$database.$schema.$table"
            tableMap = tableMap.updatedWith(key) {
              case Some(existing) => Some(existing ++ entries)
              case None           => Some(entries)
            }
          case GrantTarget.All =>
            // Ignored: not produced by YAML loader, reserved for future programmatic use
            ()
    }

    new GrantIndex(dbMap, schemaMap, tableMap, expDbMap, expSchemaMap, expTableMap)
