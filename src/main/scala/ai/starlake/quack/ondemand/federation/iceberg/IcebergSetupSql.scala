package ai.starlake.quack.ondemand.federation.iceberg

import ai.starlake.quack.model.SqlLiterals.{duckdbIdent as ident, duckdbLiteral as lit}

/** Authors the `INSTALL` + `CREATE SECRET` + `ATTACH` block for one Iceberg REST catalog, single
  * source of truth in the same spirit as `NodeLockdown` and `ObjectStoreSecret`.
  *
  * Output carries `{{secret.NAME}}` placeholders verbatim: this runs BEFORE
  * [[ai.starlake.quack.ondemand.federation.FederationBlobBuilder]] substitutes and SQL-escapes the
  * resolved values, so nothing here may resolve a secret.
  *
  * Mapping onto DuckDB v1.5.4, which is narrower than it looks:
  *   - `oauth2` and `token` both carry their credential on the ICEBERG secret and emit NO
  *     `AUTHORIZATION_TYPE`. DuckDB defaults that option to `oauth2`, which a token-bearing secret
  *     satisfies without performing an exchange.
  *   - `none` and `sigv4` need no secret at all, only the `AUTHORIZATION_TYPE` option.
  *   - `glue` and `s3_tables` are `ENDPOINT_TYPE` values and select their own signing. DuckDB
  *     refuses `ENDPOINT_TYPE` combined with `AUTHORIZATION_TYPE`, which is why
  *     [[IcebergRestConfig.validate]] permits exactly one of the two - the config type itself does
  *     not enforce this, see the precondition on [[render]].
  */
object IcebergSetupSql:

  /** Per-alias secret name. The alias arrives already normalized (lowercase, via
    * [[IcebergRestConfig.validated]]), so no lowercasing happens here - doing it here used to let
    * "Sales" and "sales" mint the same secret name without either alias being rejected.
    */
  def secretName(alias: String): String = "qod_ice_" + alias

  /** Precondition: `v` must come from [[IcebergRestConfig.validated]], which the
    * `ValidatedIcebergConfig` type enforces - there is no other way to construct one. `render` is a
    * pure total function over `v.config` - it never re-validates, so this only holds because
    * `validated` already ran every rule. Skipping it (impossible through this API) could render,
    * for example, an ATTACH carrying both `ENDPOINT_TYPE` and `AUTHORIZATION_TYPE`, which DuckDB
    * refuses at ATTACH time. Because this SQL runs inside a node's startup script, that failure
    * takes down the whole node's init, not just this one catalog.
    */
  def render(v: ValidatedIcebergConfig): String =
    val cfg    = v.config
    val alias  = v.alias
    val secret = secretBlock(cfg, alias)
    val opts   = attachOptions(cfg, alias, secret.nonEmpty)
    "INSTALL iceberg; LOAD iceberg;\n" +
      secret +
      s"ATTACH ${lit(cfg.warehouse)} AS ${ident(alias)} (\n  " +
      opts.mkString(",\n  ") +
      "\n);"

  private def secretBlock(cfg: IcebergRestConfig, alias: String): String =
    val params = cfg.authType match
      case Some(IcebergAuthType.OAuth2) =>
        List(
          Some("TYPE ICEBERG"),
          cfg.clientId.filter(_.trim.nonEmpty).map(v => s"CLIENT_ID ${lit(v)}"),
          cfg.clientSecret.filter(_.trim.nonEmpty).map(v => s"CLIENT_SECRET ${lit(v)}"),
          cfg.oauth2ServerUri.filter(_.trim.nonEmpty).map(v => s"OAUTH2_SERVER_URI ${lit(v)}"),
          cfg.oauth2Scope.filter(_.trim.nonEmpty).map(v => s"OAUTH2_SCOPE ${lit(v)}"),
          cfg.oauth2GrantType.filter(_.trim.nonEmpty).map(v => s"OAUTH2_GRANT_TYPE ${lit(v)}"),
          // DuckDB derives the oauth2 server URI fallback (<endpoint>/v1/oauth/tokens) from the
          // SECRET's own ENDPOINT, not from the ATTACH's ENDPOINT option: with authType=oauth2
          // and no explicit oauth2ServerUri, omitting this makes ATTACH fail before any network
          // call ("no 'oauth2_server_uri' was provided, and no 'endpoint' was provided to fall
          // back on"). Kept oauth2-only: a Token secret does no exchange, so ENDPOINT here would
          // be inert, and the ATTACH already carries its own ENDPOINT for catalog operations.
          Option.when(cfg.uri.trim.nonEmpty)(s"ENDPOINT ${lit(cfg.uri.trim)}")
        ).flatten
      case Some(IcebergAuthType.Token) =>
        List(
          Some("TYPE ICEBERG"),
          cfg.token.filter(_.trim.nonEmpty).map(v => s"TOKEN ${lit(v)}")
        ).flatten
      case _ => Nil

    if params.isEmpty then ""
    else
      s"CREATE OR REPLACE SECRET ${ident(secretName(alias))} (\n  " +
        params.mkString(",\n  ") +
        "\n);\n"

  private def attachOptions(
      cfg: IcebergRestConfig,
      alias: String,
      hasSecret: Boolean
  ): List[String] =
    List(
      Some("TYPE ICEBERG"),
      Option.when(hasSecret)(s"SECRET ${ident(secretName(alias))}"),
      Option.when(cfg.uri.trim.nonEmpty)(s"ENDPOINT ${lit(cfg.uri.trim)}"),
      cfg.endpointType.map(t => s"ENDPOINT_TYPE ${lit(t.wire)}"),
      cfg.authType.collect {
        case IcebergAuthType.NoAuth => s"AUTHORIZATION_TYPE ${lit(IcebergAuthType.NoAuth.wire)}"
        case IcebergAuthType.SigV4  => s"AUTHORIZATION_TYPE ${lit(IcebergAuthType.SigV4.wire)}"
      }
    ).flatten
