package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.iceberg.{AwsCredentials, IcebergRestConfig}
import ai.starlake.quack.ondemand.federation.rdbms.{RdbmsConfig, RdbmsKind}
import io.circe.Json

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

  /** Whether a write leaving `source` in place of `stored` changes what a source holding secrets
    * may do with them: another connection or config, or a read-only source left writable. Judged on
    * the row the write would leave, so a field left out counts as what it resolves to.
    */
  def changesUseOfSecrets(stored: FederatedSource, source: FederatedSource): Boolean =
    !sameConfig(source.sourceType, stored.config, source.config) ||
      (stored.readOnly && !source.readOnly)

  /** Whether two `config` values of a `t` source say the same, every field included: each read as
    * its type reads it (a connection normalized as it renders), so a key's order, a null, an absent
    * field, blank text, an empty list and surrounding spaces read alike, and a client sending the
    * stored values back changes nothing. A value that does not read stands for itself.
    */
  def sameConfig(t: FederatedSourceType, a: Option[String], b: Option[String]): Boolean =
    configValue(t, a) == configValue(t, b)

  private def configValue(t: FederatedSourceType, config: Option[String]): Json =
    config.map(_.trim).filter(_.nonEmpty).fold(Json.Null) { raw =>
      val typed = t match
        case FederatedSourceType.IcebergRest => IcebergRestConfig.fromJson(raw).map(_.toJson)
        case FederatedSourceType.Postgres | FederatedSourceType.Mysql =>
          RdbmsConfig.fromJson(raw).map(_.normalized(RdbmsKind.of(t).get).toJson)
        case FederatedSourceType.Sql => Right(raw)
      typed.toOption.flatMap(io.circe.parser.parse(_).toOption).fold(Json.fromString(raw))(settled)
    }

  /** `j` with text trimmed, and with whatever reads as nothing left out. */
  private def settled(j: Json): Json =
    def nothing(v: Json) =
      v.isNull || v.asString.exists(_.isEmpty) || v.asArray.exists(_.isEmpty) ||
        v.asObject.exists(_.isEmpty)
    j.fold(
      Json.Null,
      Json.fromBoolean,
      Json.fromJsonNumber,
      s => Json.fromString(s.trim),
      values => Json.fromValues(values.map(settled).filterNot(nothing)),
      fields => Json.fromJsonObject(fields.mapValues(settled).filter((_, v) => !nothing(v)))
    )

  /** Whether writing `source` over `stored` has a node sign S3 requests with its own identity
    * (IRSA, an instance role, inherited `AWS_*`) over a scope and a catalog the source names, which
    * is the same reach an `externalRef` has, so only a superuser may. A client sending a stored
    * source back unchanged asks for nothing new.
    */
  def adoptsNodeIdentity(stored: Option[FederatedSource], source: FederatedSource): Boolean =
    usesCredentialChain(source) && !stored.exists(s =>
      s.sourceType == source.sourceType && sameConfig(source.sourceType, s.config, source.config)
    )

  private def usesCredentialChain(source: FederatedSource): Boolean =
    source.sourceType == FederatedSourceType.IcebergRest && source.config.exists(raw =>
      IcebergRestConfig
        .fromJson(raw)
        .exists(_.awsCredentials.contains(AwsCredentials.CredentialChain))
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
