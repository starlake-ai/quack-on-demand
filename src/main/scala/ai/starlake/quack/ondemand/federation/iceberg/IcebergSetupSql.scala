package ai.starlake.quack.ondemand.federation.iceberg

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
  *     refuses `ENDPOINT_TYPE` combined with `AUTHORIZATION_TYPE`, which is why the config type
  *     permits exactly one of the two.
  */
object IcebergSetupSql:

  private def lit(v: String): String   = "'" + v.replace("'", "''") + "'"
  private def ident(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

  /** Per-alias secret name. The alias is validated as a plain identifier upstream
    * ([[IcebergRestConfig.validate]]), so lowercasing yields a safe, collision-free slug.
    */
  def secretName(alias: String): String = "qod_ice_" + alias.toLowerCase

  def render(cfg: IcebergRestConfig, alias: String): String =
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
          cfg.oauth2GrantType.filter(_.trim.nonEmpty).map(v => s"OAUTH2_GRANT_TYPE ${lit(v)}")
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
