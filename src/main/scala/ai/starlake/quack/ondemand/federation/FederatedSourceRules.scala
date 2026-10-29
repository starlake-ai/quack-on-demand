package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.iceberg.IcebergRestConfig
import ai.starlake.quack.ondemand.federation.rdbms.{RdbmsConfig, RdbmsKind}

import java.util.Locale

/** Every rule a stored federated-source row must satisfy, whichever path writes it: REST and MCP
  * (`FederatedSourceHandlers.toSource`) and manifest import (`ManifestImporter`). One function, so
  * no path admits what another refuses: a manifest row used to skip the typed config check, so a
  * postgres config with a literal password or a socket host was stored, echoed back in the clear on
  * every read path, and refused only at node spawn, where it failed the whole tenant-db's blob.
  *
  * Wire-only rules (a field sent on the wrong request member) stay with the REST handler, which is
  * the only path that has those members.
  */
object FederatedSourceRules:

  /** Secret-name prefixes QoD's own rendered blocks create and drop on a node: `qod_pg_` and
    * `qod_mysql_` (Postgres / MySQL, dropped right after their ATTACH), `qod_ice_` (Iceberg). A
    * `sql` source naming one could be replaced or dropped by a typed sibling's block, so its
    * setupSql may not mention them. The check judges the setupSql text with `{{alias}}` expanded
    * and prevents accidental collisions only: a `{{secret.NAME}}` value is spliced in verbatim at
    * spawn and never scanned, so a name spelled deliberately through a secret value passes. Not a
    * boundary: the same author can already DETACH or edit a sibling.
    */
  val ReservedSecretPrefixes: List[String] = List("qod_pg_", "qod_mysql_", "qod_ice_")

  /** Why a `sql` source's `setupSql` may not be stored for its secret placeholders (CONTRACT
    * Addendum 4 item 29, amended): one sits outside a single-quoted literal, where a tenant admin
    * who writes the secret's value could end the literal, comment or identifier around it and write
    * SQL the nodes run as the superuser's. Names the placeholders, never a value.
    */
  def unquotedSecretError(setupSql: String): Option[String] =
    SecretPlaceholderScan.unquoted(setupSql) match
      case Nil   => None
      case names =>
        Some(
          s"setupSql holds ${names.map(n => s"{{secret.$n}}").mkString(", ")} outside a " +
            "single-quoted literal: every secret placeholder of a sql source must sit inside " +
            "'...', where its value stays a value (and no escape string E'...', dollar quoting " +
            "or $ parameter may appear outside a literal)"
        )

  /** Empty when `source` may be stored. `reserved` is the tenant-db's own catalog alias plus every
    * OTHER source's alias in it, as the blob builder reserves them at spawn.
    */
  def errors(source: FederatedSource, reserved: Set[String]): List[String] =
    val t            = source.sourceType
    val setupOnTyped =
      Option.when(t.rendered && source.setupSql.trim.nonEmpty)(
        s"setupSql is for sourceType 'sql', not '${t.wire}'"
      )
    val config = source.config.filter(_.trim.nonEmpty)
    val typed  = t match
      case FederatedSourceType.Sql =>
        // Judged on the setupSql text with `{{alias}}` expanded, so `qod_{{alias}}` under alias
        // `pg_x` is `qod_pg_x`. Secret values are not spliced in here (see ReservedSecretPrefixes).
        val lower = source.setupSql.replace("{{alias}}", source.alias).toLowerCase(Locale.ROOT)
        ReservedSecretPrefixes.filter(lower.contains).map { p =>
          s"setupSql mentions '$p', a secret-name prefix reserved for QoD's typed sources"
        } ++ unquotedSecretError(source.setupSql).toList
      case FederatedSourceType.IcebergRest =>
        config.toList.flatMap { json =>
          IcebergRestConfig.fromJson(json) match
            case Left(e)    => List(s"config is not a valid iceberg_rest declaration: $e")
            case Right(cfg) =>
              IcebergRestConfig.validated(cfg, source.alias, reserved).left.getOrElse(Nil)
        }
      case FederatedSourceType.Postgres | FederatedSourceType.Mysql =>
        val kind = RdbmsKind.of(t).get
        config.toList.flatMap { json =>
          RdbmsConfig.fromJson(json) match
            case Left(e)    => List(s"config is not a valid ${t.wire} declaration: $e")
            case Right(cfg) =>
              RdbmsConfig.validated(kind, cfg, source.alias, reserved).left.getOrElse(Nil)
        }
    (setupOnTyped.toList ++ source.validate ++ typed).distinct
