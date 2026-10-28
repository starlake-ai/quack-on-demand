package ai.starlake.quack.edge.sql

import java.util.Locale
import ai.starlake.acl.model.{Config, DenyReason}
import ai.starlake.acl.parser.{
  ExtractionResult,
  SqlParser,
  StatementResult,
  TableAccess,
  TableExtractor,
  Verb
}

/** What a statement needs authorized, independent of WHO decides (QoD grants or OPA).
  *   - `Refuse`: the statement cannot be authorized table by table. `wildcardCoverable` says
  *     whether QoD's unrestricted `*.*.* ALL` grant may still admit it; OPA always denies.
  *     `covered` names what the wildcard admitted, for the allow log line.
  *   - `NothingGated`: control flow, or every access was answered by the metadata filter.
  *   - `Gated`: the accesses that need a decision (`accesses` is the full set, for logging).
  */
enum GateOutcome:
  case Refuse(message: String, wildcardCoverable: Boolean, covered: String)
  case NothingGated(accesses: Set[TableAccess], catalogFunctions: List[String])
  case Gated(gated: Set[TableAccess], accesses: Set[TableAccess])

/** The gated-access computation shared by every statement validator, so each one authorizes exactly
  * the same `(table, verb)` set. Pure: no logging, no grant lookup.
  */
object AccessGate:

  /** The refusal of a two-part name headed by an attached catalog, worded once for every step that
    * refuses it (this gate, [[CatalogWriteScreen]], the protected-write guard).
    */
  def ambiguousNameReason(a: DenyReason.AmbiguousCatalogRef): String =
    s"ambiguous two-part name '${a.tableName}': '${a.catalog}' is an attached catalog; " +
      s"qualify fully as '${a.catalog}.<schema>.<table>'"

  /** The SQL parser configuration for a session: the pool's metastore catalog / schema fill in
    * unqualified references, falling back to the validator's defaults.
    */
  def parserConfig(
      context: ValidationContext,
      defaultDatabase: String,
      defaultSchema: String,
      dialect: String
  ): Config =
    val db     = context.defaultDatabase.getOrElse(defaultDatabase)
    val schema = context.defaultSchema.getOrElse(defaultSchema)
    if dialect.equalsIgnoreCase("duckdb") then
      Config.forDuckDB(Some(db), Some(schema), context.attachedCatalogs)
    else Config.forGeneric(db, schema)

  /** `sessionCatalog` must be the same catalog `parserConfig` used as its default database, so the
    * filtered-metadata admit and the parser's qualification agree (see below).
    *
    * `clientDataScans` is [[ValidationContext.clientDataScans]]: true only for a statement from the
    * native Quack front door. Required, so no caller can admit the scan by omission.
    *
    * `admission`: the locations the principal's tenant authorized; a file reader or a lone COPY
    * naming only those is judged by its table side alone ([[FileLocations]]).
    */
  def evaluate(
      statement: String,
      config: Config,
      sessionCatalog: String,
      filteredMetadata: Boolean,
      clientDataScans: Boolean,
      rewriteOf: Option[String] = None,
      admission: FileAdmission = FileAdmission.Closed
  ): GateOutcome =
    // Parsed once, by the file screen (when its lexical checks find nothing) or the gate below.
    // An admitted COPY is parsed as its table side, which JSqlParser can read.
    lazy val extraction =
      SqlParser.extract(FileLocations.extractable(statement, admission), config)
    // Ahead of every other arm, and never wildcard-coverable: the target of these calls is
    // a string the parser cannot see, so the wildcard's tenant-catalog scoping cannot apply.
    // The same holds for a batch that moves the schema or search path: every bare name after
    // it resolves somewhere `config` does not describe, possibly in a catalog outside the
    // tenant's (see SessionScopeScreen). The backtick screen goes first: on a DuckDB 1.x engine a
    // backtick pair can hide separators and calls from both screens after it (see BacktickScreen).
    // A SET of a SQL-valued setting is control flow to the parser, but its value runs later on the
    // catalog Postgres or as the node's auth callback, outside every grant.
    // A quoted name holding a dot is one identifier to DuckDB and a qualified name to the parser
    // (see DottedIdentifierScreen), so the grant checked would not be the table read. A file read
    // or write by path is no table a grant names, and is reserved to superusers whatever the
    // grants (FileAccessScreen).
    BacktickScreen
      .find(statement)
      .orElse(DottedIdentifierScreen.find(statement, rewriteOf))
      .orElse(CatalogReachingCalls.find(statement).map(CatalogReachingCalls.denyReason))
      .orElse(FileAccessScreen.find(statement, extraction, admission))
      .orElse(
        CatalogReachingCalls.sqlValuedSetting(statement).map(CatalogReachingCalls.settingDenyReason)
      )
      .orElse(SessionScopeScreen.find(statement)) match
      case Some(reason) => GateOutcome.Refuse(reason, false, "")
      case None         =>
        evaluateParsed(
          statement,
          extraction,
          config,
          sessionCatalog,
          filteredMetadata,
          clientDataScans,
          FileLocations.admittedReaderNames(statement, admission)
        )

  /** Some(reason) when `rewritten`, a statement an edge rewriter (column policies, row policies,
    * metadata filter) printed from `original`, needs a grant on a table, or a verb, the original
    * did not, or can no longer be authorized table by table while the original could. The router
    * authorized `original` and runs `rewritten`: a rewrite that reaches another table (a CTE
    * reference printed schema-qualified becomes a read of the real table of that name) would run a
    * read nobody checked. Both are evaluated under the same `config`, so an unqualified name
    * qualifies identically on both sides.
    *
    * Only the accesses that need a grant are compared: the session catalog's filterable metadata
    * reads and catalog functions are answered by the metadata filter, which has already run on
    * `rewritten` (it is the rewriter that introduces them, for SHOW TABLES). An original that is
    * itself refused (admitted by a wildcard grant) cannot be compared here; the router re-validates
    * every rewrite anyway.
    */
  def rewriteReachesBeyond(
      original: String,
      rewritten: String,
      config: Config,
      sessionCatalog: String,
      clientDataScans: Boolean
  ): Option[String] =
    def gatedOf(o: GateOutcome): Option[Set[TableAccess]] = o match
      case GateOutcome.Refuse(_, _, _)    => None
      case GateOutcome.NothingGated(_, _) => Some(Set.empty)
      case GateOutcome.Gated(gated, _)    => Some(gated)
    def eval(sql: String, rewriteOf: Option[String]) =
      evaluate(sql, config, sessionCatalog, filteredMetadata = true, clientDataScans, rewriteOf)
    gatedOf(eval(original, None)).flatMap { authorized =>
      eval(rewritten, Some(original)) match
        case GateOutcome.Refuse(message, _, _) =>
          Some(s"the rewritten statement cannot be authorized ($message)")
        case other =>
          gatedOf(other).flatMap { needed =>
            val extra = (needed -- authorized).map(a => s"${a.table.canonical} (${a.verb})")
            Option.when(extra.nonEmpty)(
              "the rewritten statement reaches what the original did not: " +
                extra.toList.sorted.mkString(", ")
            )
          }
    }

  private def evaluateParsed(
      statement: String,
      extraction: ExtractionResult,
      config: Config,
      sessionCatalog: String,
      filteredMetadata: Boolean,
      clientDataScans: Boolean,
      admittedReaders: Set[String]
  ): GateOutcome =

    // Parse errors short-circuit to Denied unless the principal holds an
    // unrestricted ALL grant (preserves prior behavior).
    val parseErrors = extraction.statements.collect {
      case StatementResult.ParseError(_, snippet, msg) => s"parse error: $msg ($snippet)"
    }
    // Constructs the walker could not resolve to a grantable table (table
    // functions like read_parquet, string-literal file refs, unrecognized
    // FROM-item / node types). Fail closed: these escape the tenant-catalog
    // boundary or would otherwise be silently dropped, turning the
    // empty-access fail-open into an allow. Wildcard ALL still covers them.
    //
    // Filtered-metadata carve-out (issue #114): the DuckDB catalog functions the
    // edge rewriter filters are set aside here and admitted below, but only once
    // the statement is known to be a pure read. The marker is the parser's own
    // (TableExtractor.tableFunctionName reads it back), and the call must be
    // exactly `<function>()`: `duckdb_tables('x')`, `main.duckdb_tables()` and the
    // bare-name marker for `FROM duckdb_tables` are not that shape and stay
    // unsupported.
    //
    // A DuckDB 2.0 client's INSERT through an attached catalog reads its rows from
    // scan_data_from_quack_client, which reads only what the same connection sends: as the
    // source of a write it is set aside here, the INSERT target staying a gated Write
    // (TableExtractor.gatedUnsupported). Only on the native front door, the one edge that
    // relays SEND_DATA: from FlightSQL, MCP or `qod sql` nothing can feed the stream, the
    // statement would park on the node forever, so the scan stays unsupported there.
    //
    // A file reader whose every location the tenant authorized (Task 12) is decided by the location
    // rule the file screen applied, not by a grant: its "table function" marker is set aside.
    val allUnsupported = extraction.statements
      .collect {
        case StatementResult.Extracted(_, _, a, _, u) if u.nonEmpty =>
          TableExtractor.gatedUnsupported(a, u, clientDataScans)
      }
      .flatten
      .filterNot { u =>
        TableExtractor.tableFunctionName(u).map(FileReferences.callName).exists(admittedReaders)
      }
    val (catalogFunctions, parsedUnsupported) =
      if filteredMetadata then
        allUnsupported.partition(u =>
          TableExtractor.tableFunctionName(u).flatMap(TableExtractor.catalogFunctionCall).isDefined
        )
      else (Nil, allUnsupported)
    // A scalar call returning another object's definition by oid (`pg_get_viewdef`), outside the
    // metadata filter: unsupported like the listing it reads (CatalogDefinitionCalls).
    val unsupported = parsedUnsupported ++ CatalogDefinitionCalls.markers(statement)
    // Qualification errors were previously ignored, silently DROPPING the
    // offending ref from the access set (fail-open). AmbiguousCatalogRef denies
    // unconditionally: admitting it under the wildcard would reopen the
    // cross-catalog bypass the attached-catalog check exists to close. Other
    // qualification errors mirror the unsupported arm (wildcard ALL covers them).
    val qualErrors = extraction.statements.collect {
      case StatementResult.Extracted(_, _, _, q, _) if q.nonEmpty => q
    }.flatten
    val ambiguous = qualErrors.collect { case a: DenyReason.AmbiguousCatalogRef => a }
    val otherQual = qualErrors.filterNot(_.isInstanceOf[DenyReason.AmbiguousCatalogRef])

    if parseErrors.nonEmpty then
      GateOutcome.Refuse(parseErrors.mkString("; "), true, "unparseable statement")
    else if unsupported.nonEmpty then
      GateOutcome.Refuse(
        s"unsupported constructs (deny, fail-closed): ${unsupported.mkString(", ")}",
        true,
        s"unsupported constructs (${unsupported.mkString(", ")})"
      )
    else if ambiguous.nonEmpty then
      GateOutcome.Refuse(
        ambiguous.map(ambiguousNameReason).mkString("; "),
        false,
        ""
      )
    else if otherQual.nonEmpty then
      GateOutcome.Refuse(
        s"unresolvable table references (deny, fail-closed): ${otherQual.mkString("; ")}",
        true,
        s"qualification errors (${otherQual.mkString(", ")})"
      )
    else
      // Collect all (table, verb) tuples from every Extracted statement.
      // ControlFlow statements (COMMIT, ROLLBACK, SET, ...) carry no accesses
      // and contribute nothing.
      val accesses: Set[TableAccess] = extraction.statements
        .collect { case StatementResult.Extracted(_, _, a, _, _) =>
          a
        }
        .flatten
        .toSet

      // Filtered-metadata implicit admit: Read accesses on the session catalog's
      // filterable information_schema tables are answered by the edge metadata
      // filter (same flag), so they need no grant here. Everything else about
      // system schemas (writes, DDL, unlisted tables, cross-catalog refs) still
      // requires a grant. The flag wires from the SAME AclConfig value that
      // mounts the rewriter, so admit-without-filter cannot happen.
      //
      // SESSION catalog only. Never the tenant catalog set: the rewriter filters
      // only session-catalog references, so admitting a SIBLING tenant-db's
      // information_schema here would be admit-without-filter (unfiltered
      // enumeration of a catalog the principal may hold zero grants on).
      // The caller passes `context.defaultDatabase.getOrElse(defaultDatabase)`,
      // the same expression `parserConfig` uses, so the admit and the parser's
      // qualification of an unqualified `information_schema.tables` agree on the
      // catalog by construction.
      //
      // PURE-READ statements only: the metadata rewriter filters Select
      // statements and passes everything else through untouched, so dropping an
      // info-schema Read that rides inside an INSERT / CTAS / MERGE would be
      // admit-without-filter (unfiltered catalog copy into a table the principal
      // owns).
      val pureRead                = accesses.forall(_.verb == Verb.Read)
      val gated: Set[TableAccess] =
        if !filteredMetadata || !pureRead then accesses
        else
          accesses.filterNot { ta =>
            ta.verb == Verb.Read &&
            ta.table.schema.equalsIgnoreCase("information_schema") &&
            ai.starlake.quack.edge.meta.MetadataFilterRewriter.FilterableTables
              .contains(ta.table.table.toLowerCase(Locale.ROOT)) &&
            ta.table.database.equalsIgnoreCase(sessionCatalog)
          }

      if catalogFunctions.nonEmpty && !pureRead then
        // A catalog function riding inside an INSERT / CTAS / MERGE: the rewriter
        // does not filter the read half of a write, so admitting it would copy an
        // unfiltered catalog listing into a table the principal owns.
        GateOutcome.Refuse(
          "catalog functions are admitted in read-only statements only (deny, fail-closed): " +
            catalogFunctions.mkString(", "),
          true,
          s"unsupported constructs (${catalogFunctions.mkString(", ")})"
        )
      else if gated.isEmpty then
        // Pure ControlFlow (or every arm yielded zero refs), or an
        // all-metadata statement whose every access the implicit admit above
        // dropped. Admit unconditionally -- there is nothing left to
        // authorize. The caller's log still names the ORIGINAL accesses so the
        // audit trail shows what was read.
        GateOutcome.NothingGated(accesses, catalogFunctions)
      else GateOutcome.Gated(gated, accesses)
