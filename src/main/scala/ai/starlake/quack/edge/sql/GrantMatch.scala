package ai.starlake.quack.edge.sql

import ai.starlake.acl.parser.Verb
import ai.starlake.quack.ondemand.state.RolePermission

import java.util.Locale

/** How role permissions cover one access: the grant matching of [[PostgresAclValidator]], shared
  * with the node decision pipeline (`edge.authz.AuthzDecider`), which matches what DuckDB resolved
  * for a statement instead of what the SQL parser extracted.
  */
object GrantMatch:

  /** Whether a role-permission verb covers an access verb. Grant verbs are the canonical (RO / RW /
    * DDL / ALL) set; accesses are the collapsed `Verb.Read | Verb.Write | Verb.Ddl`. `RW` is the
    * only multi-cover grant (Read + Write); DDL stays separate because CREATE/DROP/ALTER are
    * deliberately higher-privilege.
    */
  def verbCovers(grantVerb: String, access: Verb): Boolean =
    val gu = grantVerb.toUpperCase(Locale.ROOT)
    if gu == "ALL" then true
    else
      access match
        case Verb.Read  => gu == "RO" || gu == "RW"
        case Verb.Write => gu == "RW"
        case Verb.Ddl   => gu == "DDL"

  def wildcardMatch(grant: String, ref: String): Boolean =
    grant == RolePermission.Wildcard || grant.equalsIgnoreCase(ref)

  /** Catalog-specific wildcard match: the literal-equal arm behaves like `wildcardMatch`, but the
    * `*` arm additionally requires the referenced catalog to be in the session's allowed-catalog
    * set. This closes the cross-tenant wildcard leak (a tenant admin with `*.*.* ALL` could
    * otherwise SELECT from a sibling tenant's catalog -- see project memory
    * `project-catalog-wildcard-cross-tenant`).
    *
    * An empty `sessionCatalogs` means no catalog matches via wildcard for this session. Operators
    * who want a tenant admin to retain cross-tenant read access can still grant an explicit catalog
    * (`otherdb.*.* ALL`), which bypasses the wildcard arm via the literal-equal check below.
    */
  def catalogMatch(grant: String, ref: String, sessionCatalogs: Set[String]): Boolean =
    if grant == RolePermission.Wildcard then sessionCatalogs.exists(_.equalsIgnoreCase(ref))
    else grant.equalsIgnoreCase(ref)

  /** Whether some permission covers `verb` on `catalog.schema.table`. */
  def covers(
      permissions: List[RolePermission],
      sessionCatalogs: Set[String],
      catalog: String,
      schema: String,
      table: String,
      verb: Verb
  ): Boolean =
    permissions.exists(p =>
      verbCovers(p.verb, verb) &&
        catalogMatch(p.catalogName, catalog, sessionCatalogs) &&
        wildcardMatch(p.schemaName, schema) &&
        wildcardMatch(p.tableName, table)
    )

  /** Whether some permission covers `verb` on a whole schema: one whose table part is the wildcard.
    * CREATE / DROP / ALTER SCHEMA in the node decision pipeline.
    */
  def coversSchema(
      permissions: List[RolePermission],
      sessionCatalogs: Set[String],
      catalog: String,
      schema: String,
      verb: Verb
  ): Boolean =
    permissions.exists(p =>
      p.tableName == RolePermission.Wildcard &&
        verbCovers(p.verb, verb) &&
        catalogMatch(p.catalogName, catalog, sessionCatalogs) &&
        wildcardMatch(p.schemaName, schema)
    )

  /** Whether the permissions hold an unrestricted `*.*.* ALL` grant. */
  def hasWildcardAll(permissions: List[RolePermission]): Boolean =
    permissions.exists(p =>
      p.verb.equalsIgnoreCase("ALL") &&
        p.catalogName == RolePermission.Wildcard &&
        p.schemaName == RolePermission.Wildcard &&
        p.tableName == RolePermission.Wildcard
    )
