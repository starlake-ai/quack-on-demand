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

  /** Every `CREATE SECRET` option whose VALUE is a credential, declared once.
    *
    * This exists so the redactor does not have to keep a second copy of the list:
    * [[AttachErrorRedactor]] builds its parser from `CredentialOption.values`, and
    * [[credentialOpt]] is the ONLY way [[secretBlock]] can emit one, so a new credential-bearing
    * option cannot be rendered without first appearing here -- which widens the redactor in the
    * same edit. A non-credential option (CLIENT_ID, the oauth2 knobs, ENDPOINT) goes through
    * [[opt]] as before.
    */
  enum CredentialOption(val optionName: String):
    case ClientSecret extends CredentialOption("CLIENT_SECRET")
    case Token        extends CredentialOption("TOKEN")

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
    *
    * `readOnly` is a property of the federated-source row (`FederatedSource.readOnly`), not of the
    * catalog connection config, so it arrives as its own parameter rather than living on
    * `ValidatedIcebergConfig`. It carries NO default on purpose: a forgotten argument would render
    * an ATTACH without `READ_ONLY`, i.e. attach a source the operator marked read-only as writable,
    * and a default whose direction is "protection off" is a defect rather than a convenience. When
    * true this emits a bare `READ_ONLY` ATTACH option. What is proven about that option today:
    * DuckDB accepts it as a recognized Iceberg ATTACH option (a bogus option fails ATTACH with
    * "Unhandled options found"; this one does not), and DuckDB enforces read-only below SQL parsing
    * for a FILE-BACKED attach carrying it - e.g. `INSERT` fails with `Cannot execute statement of
    * type "INSERT" on database "..." which is attached in read-only mode!`. Whether the `iceberg`
    * extension's write paths honour that same bit against a live REST catalog is NOT yet proven end
    * to end; that verification is gated into a separate task. Until it lands, treat this as the
    * INTENDED primary enforcement of read-only for `iceberg_rest` sources, not a confirmed one, and
    * keep `CatalogWriteScreen` as defence in depth regardless (it is also the ONLY enforcement for
    * free-form `sql` sources, whose ATTACH text QoD does not control). This render call runs at
    * node spawn, so the emitted `READ_ONLY` binds for the lifetime of the attach: flipping
    * `FederatedSource.readOnly` afterwards does not change an already-running node's engine-level
    * behaviour until the pool recycles and re-renders this output, even though `CatalogWriteScreen`
    * re-reads the flag on its own ~60s cache - see `FederatedSource.readOnly`'s scaladoc for the
    * full two-layer split, including the `CALL` residual this layer is meant to close.
    */
  def render(v: ValidatedIcebergConfig, readOnly: Boolean): String =
    val cfg    = v.config
    val alias  = v.alias
    val secret = secretBlock(cfg, alias)
    val opts   = attachOptions(cfg, alias, needsSecret(cfg)) ++ Option.when(readOnly)("READ_ONLY")
    "INSTALL iceberg; LOAD iceberg;\n" +
      secret +
      s"ATTACH ${lit(cfg.warehouse.trim)} AS ${ident(alias)} (\n  " +
      opts.mkString(",\n  ") +
      "\n);"

  /** Whether `authType` requires a `CREATE SECRET` block: oauth2 and token both carry their
    * credential on the ICEBERG secret (see the class scaladoc); none / sigv4 / any endpointType
    * need none. Drives both the ATTACH's `SECRET` option and whether [[secretBlock]] emits
    * anything, so the two can never disagree about whether a secret exists.
    */
  private def needsSecret(cfg: IcebergRestConfig): Boolean =
    cfg.authType.exists(t => t == IcebergAuthType.OAuth2 || t == IcebergAuthType.Token)

  /** `NAME '<trimmed value>'`, or `None` when `value` is unset or blank after trimming. Trimming
    * here - not just at the `isSet`/`filter` check upstream - matters because
    * [[IcebergRestConfig.validate]] itself trims before matching the `{{secret.NAME}}` pattern, so
    * e.g. `clientSecret = " {{secret.CSEC}} "` passes validation; without trimming again here the
    * padding would land inside the SQL literal (and, once substituted, inside the resolved
    * credential).
    */
  private def opt(name: String, value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty).map(v => s"$name ${lit(v)}")

  /** [[opt]] for an option that carries a credential. Taking the name from [[CredentialOption]]
    * rather than a string literal is what keeps the generator and [[AttachErrorRedactor]] from
    * drifting apart.
    */
  private def credentialOpt(option: CredentialOption, value: Option[String]): Option[String] =
    opt(option.optionName, value)

  /** The oauth2-server-endpoint fallback DuckDB reads off the SECRET (see [[secretBlock]]'s
    * comment) and the ATTACH's own `ENDPOINT` are textually identical but SEMANTICALLY DISTINCT -
    * DuckDB reads one for the oauth2 token exchange and the other for catalog operations - so both
    * call sites keep their own emission even though they share this computation.
    */
  private def endpointOpt(cfg: IcebergRestConfig): Option[String] =
    Option.when(cfg.uri.trim.nonEmpty)(s"ENDPOINT ${lit(cfg.uri.trim)}")

  private def secretBlock(cfg: IcebergRestConfig, alias: String): String =
    if !needsSecret(cfg) then ""
    else
      val params = cfg.authType match
        case Some(IcebergAuthType.OAuth2) =>
          List(
            Some("TYPE ICEBERG"),
            opt("CLIENT_ID", cfg.clientId),
            credentialOpt(CredentialOption.ClientSecret, cfg.clientSecret),
            opt("OAUTH2_SERVER_URI", cfg.oauth2ServerUri),
            opt("OAUTH2_SCOPE", cfg.oauth2Scope),
            opt("OAUTH2_GRANT_TYPE", cfg.oauth2GrantType),
            // DuckDB derives the oauth2 server URI fallback (<endpoint>/v1/oauth/tokens) from the
            // SECRET's own ENDPOINT, not from the ATTACH's ENDPOINT option: with authType=oauth2
            // and no explicit oauth2ServerUri, omitting this makes ATTACH fail before any network
            // call ("no 'oauth2_server_uri' was provided, and no 'endpoint' was provided to fall
            // back on"). Kept oauth2-only: a Token secret does no exchange, so ENDPOINT here would
            // be inert, and the ATTACH already carries its own ENDPOINT for catalog operations.
            endpointOpt(cfg)
          ).flatten
        case Some(IcebergAuthType.Token) =>
          List(Some("TYPE ICEBERG"), credentialOpt(CredentialOption.Token, cfg.token)).flatten
        case _ => Nil

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
      endpointOpt(cfg),
      cfg.endpointType.map(t => s"ENDPOINT_TYPE ${lit(t.wire)}"),
      cfg.authType.collect {
        case IcebergAuthType.NoAuth => s"AUTHORIZATION_TYPE ${lit(IcebergAuthType.NoAuth.wire)}"
        case IcebergAuthType.SigV4  => s"AUTHORIZATION_TYPE ${lit(IcebergAuthType.SigV4.wire)}"
      }
    ).flatten
