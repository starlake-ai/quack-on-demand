package ai.starlake.quack.edge.cls

/** Static column lists for DuckDB's system-catalog tables (`information_schema.*` and
  * `pg_catalog.*`), so the CLS resolver can resolve metadata queries without asking the tenant
  * catalog (which only knows user tables and returns Nil for system schemas, tripping the
  * STRICT resolver into a fail-closed deny).
  *
  * These are DuckDB's FIXED system-catalog shapes: unlike user tables they do not vary per
  * tenant or per database, only per DuckDB version. Every list below was obtained by probing a
  * real DuckDB (v1.5.4) with
  * {{{
  *   duckdb :memory: -c "CREATE TABLE _x(a int); DESCRIBE SELECT * FROM <schema>.<table>"
  * }}}
  * and copying the column names in order. Names not present in that build (e.g.
  * `information_schema.tables_extensions`, `information_schema.routines`) are deliberately
  * omitted.
  *
  * Soundness: column policies only ever target user-schema tables, so resolving a system-schema
  * table can never unmask a user column; the rewrite over a pure metadata query is a no-op
  * passthrough. A system table that is NOT in this map simply stays absent from the resolver's
  * schema, and the STRICT resolver denies: OMISSIONS FAIL CLOSED (worst case is a denied
  * metadata query, never a data leak). Keep it that way: never add a fallback that passes
  * unknown system tables through unresolved.
  */
object SystemSchemaColumns:

  /** Schemas treated as system schemas (case-insensitive). */
  private val systemSchemas: Set[String] = Set("information_schema", "pg_catalog")

  def isSystemSchema(schema: String): Boolean =
    systemSchemas.contains(schema.toLowerCase)

  /** Ordered column list for `schema.table`, case-insensitive on both parts. `None` when the
    * schema is not a system schema or the table is not a known system table of this DuckDB
    * build (callers then fall back to their normal fail-closed path).
    */
  def columnsOf(schema: String, table: String): Option[List[String]] =
    if !isSystemSchema(schema) then None
    else tables.get((schema.toLowerCase, table.toLowerCase))

  /** Every known system table as ((schema, table) -> ordered columns), for seeding a resolver's
    * metadata with schema-qualified entries.
    */
  def all: Map[(String, String), List[String]] = tables

  private val informationSchema: Map[String, List[String]] = Map(
    "schemata" -> List(
      "catalog_name",
      "schema_name",
      "schema_owner",
      "default_character_set_catalog",
      "default_character_set_schema",
      "default_character_set_name",
      "sql_path"
    ),
    "tables" -> List(
      "table_catalog",
      "table_schema",
      "table_name",
      "table_type",
      "self_referencing_column_name",
      "reference_generation",
      "user_defined_type_catalog",
      "user_defined_type_schema",
      "user_defined_type_name",
      "is_insertable_into",
      "is_typed",
      "commit_action",
      "TABLE_COMMENT"
    ),
    "columns" -> List(
      "table_catalog",
      "table_schema",
      "table_name",
      "column_name",
      "ordinal_position",
      "column_default",
      "is_nullable",
      "data_type",
      "character_maximum_length",
      "character_octet_length",
      "numeric_precision",
      "numeric_precision_radix",
      "numeric_scale",
      "datetime_precision",
      "interval_type",
      "interval_precision",
      "character_set_catalog",
      "character_set_schema",
      "character_set_name",
      "collation_catalog",
      "collation_schema",
      "collation_name",
      "domain_catalog",
      "domain_schema",
      "domain_name",
      "udt_catalog",
      "udt_schema",
      "udt_name",
      "scope_catalog",
      "scope_schema",
      "scope_name",
      "maximum_cardinality",
      "dtd_identifier",
      "is_self_referencing",
      "is_identity",
      "identity_generation",
      "identity_start",
      "identity_increment",
      "identity_maximum",
      "identity_minimum",
      "identity_cycle",
      "is_generated",
      "generation_expression",
      "is_updatable",
      "COLUMN_COMMENT"
    ),
    "views" -> List(
      "table_catalog",
      "table_schema",
      "table_name",
      "view_definition",
      "check_option",
      "is_updatable",
      "is_insertable_into",
      "is_trigger_updatable",
      "is_trigger_deletable",
      "is_trigger_insertable_into"
    ),
    "table_constraints" -> List(
      "constraint_catalog",
      "constraint_schema",
      "constraint_name",
      "table_catalog",
      "table_schema",
      "table_name",
      "constraint_type",
      "is_deferrable",
      "initially_deferred",
      "enforced",
      "nulls_distinct"
    ),
    "key_column_usage" -> List(
      "constraint_catalog",
      "constraint_schema",
      "constraint_name",
      "table_catalog",
      "table_schema",
      "table_name",
      "column_name",
      "ordinal_position",
      "position_in_unique_constraint"
    ),
    "referential_constraints" -> List(
      "constraint_catalog",
      "constraint_schema",
      "constraint_name",
      "unique_constraint_catalog",
      "unique_constraint_schema",
      "unique_constraint_name",
      "match_option",
      "update_rule",
      "delete_rule"
    ),
    "character_sets" -> List(
      "character_set_catalog",
      "character_set_schema",
      "character_set_name",
      "character_repertoire",
      "form_of_use",
      "default_collate_catalog",
      "default_collate_schema",
      "default_collate_name"
    ),
    "check_constraints" -> List(
      "constraint_catalog",
      "constraint_schema",
      "constraint_name",
      "check_clause"
    ),
    "constraint_column_usage" -> List(
      "table_catalog",
      "table_schema",
      "table_name",
      "column_name",
      "constraint_catalog",
      "constraint_schema",
      "constraint_name",
      "constraint_type",
      "constraint_text"
    ),
    "constraint_table_usage" -> List(
      "table_catalog",
      "table_schema",
      "table_name",
      "constraint_catalog",
      "constraint_schema",
      "constraint_name",
      "constraint_type"
    )
  )

  private val pgCatalog: Map[String, List[String]] = Map(
    "pg_tables" -> List(
      "schemaname",
      "tablename",
      "tableowner",
      "tablespace",
      "hasindexes",
      "hasrules",
      "hastriggers"
    ),
    "pg_class" -> List(
      "oid",
      "relname",
      "relnamespace",
      "reltype",
      "reloftype",
      "relowner",
      "relam",
      "relfilenode",
      "reltablespace",
      "relpages",
      "reltuples",
      "relallvisible",
      "reltoastrelid",
      "reltoastidxid",
      "relhasindex",
      "relisshared",
      "relpersistence",
      "relkind",
      "relnatts",
      "relchecks",
      "relhasoids",
      "relhaspkey",
      "relhasrules",
      "relhastriggers",
      "relhassubclass",
      "relrowsecurity",
      "relispopulated",
      "relreplident",
      "relispartition",
      "relrewrite",
      "relfrozenxid",
      "relminmxid",
      "relacl",
      "reloptions",
      "relpartbound"
    ),
    "pg_namespace" -> List("oid", "nspname", "nspowner", "nspacl"),
    "pg_attribute" -> List(
      "attrelid",
      "attname",
      "atttypid",
      "attstattarget",
      "attlen",
      "attnum",
      "attndims",
      "attcacheoff",
      "atttypmod",
      "attbyval",
      "attstorage",
      "attalign",
      "attnotnull",
      "atthasdef",
      "atthasmissing",
      "attidentity",
      "attgenerated",
      "attisdropped",
      "attislocal",
      "attinhcount",
      "attcollation",
      "attcompression",
      "attacl",
      "attoptions",
      "attfdwoptions",
      "attmissingval"
    ),
    "pg_type" -> List(
      "oid",
      "typname",
      "typnamespace",
      "typowner",
      "typlen",
      "typbyval",
      "typtype",
      "typcategory",
      "typispreferred",
      "typisdefined",
      "typdelim",
      "typrelid",
      "typsubscript",
      "typelem",
      "typarray",
      "typinput",
      "typoutput",
      "typreceive",
      "typsend",
      "typmodin",
      "typmodout",
      "typanalyze",
      "typalign",
      "typstorage",
      "typnotnull",
      "typbasetype",
      "typtypmod",
      "typndims",
      "typcollation",
      "typdefaultbin",
      "typdefault",
      "typacl"
    ),
    "pg_database" -> List("oid", "datname", "datallowconn", "datistemplate"),
    "pg_settings" -> List("name", "setting", "short_desc", "vartype"),
    "pg_views"    -> List("schemaname", "viewname", "viewowner", "definition"),
    "pg_am"       -> List("oid", "amname", "amhandler", "amtype"),
    "pg_attrdef"  -> List("oid", "adrelid", "adnum", "adbin"),
    "pg_constraint" -> List(
      "oid",
      "conname",
      "connamespace",
      "contype",
      "condeferrable",
      "condeferred",
      "convalidated",
      "conrelid",
      "contypid",
      "conindid",
      "conparentid",
      "confrelid",
      "confupdtype",
      "confdeltype",
      "confmatchtype",
      "conislocal",
      "coninhcount",
      "connoinherit",
      "conkey",
      "confkey",
      "conpfeqop",
      "conppeqop",
      "conffeqop",
      "conexclop",
      "conbin"
    ),
    "pg_depend" -> List(
      "classid",
      "objid",
      "objsubid",
      "refclassid",
      "refobjid",
      "refobjsubid",
      "deptype"
    ),
    "pg_description" -> List("objoid", "classoid", "objsubid", "description"),
    "pg_enum"        -> List("oid", "enumtypid", "enumsortorder", "enumlabel"),
    "pg_index" -> List(
      "indexrelid",
      "indrelid",
      "indnatts",
      "indnkeyatts",
      "indisunique",
      "indisprimary",
      "indisexclusion",
      "indimmediate",
      "indisclustered",
      "indisvalid",
      "indcheckxmin",
      "indisready",
      "indislive",
      "indisreplident",
      "indkey",
      "indcollation",
      "indclass",
      "indoption",
      "indexprs",
      "indpred"
    ),
    "pg_indexes" -> List("schemaname", "tablename", "indexname", "tablespace", "indexdef"),
    "pg_proc" -> List(
      "oid",
      "proname",
      "pronamespace",
      "proowner",
      "prolang",
      "procost",
      "prorows",
      "provariadic",
      "prosupport",
      "prokind",
      "prosecdef",
      "proleakproof",
      "proisstrict",
      "proretset",
      "provolatile",
      "proparallel",
      "pronargs",
      "pronargdefaults",
      "prorettype",
      "proargtypes",
      "proallargtypes",
      "proargmodes",
      "proargnames",
      "proargdefaults",
      "protrftypes",
      "prosrc",
      "probin",
      "prosqlbody",
      "proconfig",
      "proacl",
      "proisagg"
    ),
    "pg_sequence" -> List(
      "seqrelid",
      "seqtypid",
      "seqstart",
      "seqincrement",
      "seqmax",
      "seqmin",
      "seqcache",
      "seqcycle"
    ),
    "pg_sequences" -> List(
      "schemaname",
      "sequencename",
      "sequenceowner",
      "data_type",
      "start_value",
      "min_value",
      "max_value",
      "increment_by",
      "cycle",
      "cache_size",
      "last_value"
    )
  )

  private val tables: Map[(String, String), List[String]] =
    informationSchema.map { case (t, cols) => ("information_schema", t) -> cols } ++
      pgCatalog.map { case (t, cols) => ("pg_catalog", t) -> cols }
