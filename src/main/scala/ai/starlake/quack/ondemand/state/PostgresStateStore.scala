package ai.starlake.quack.ondemand.state

import io.circe.parser._
import io.circe.syntax._

import java.sql.{Connection, DriverManager}

/** Stores the [[StoredState]] blob in a single-row Postgres table.
  *
  * Schema:
  * {{{
  *   CREATE TABLE slkstate_pool_state (
  *     id         INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  *     content    JSONB   NOT NULL,
  *     updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
  *   )
  * }}}
  *
  * Upserts a single row keyed on `id = 1`. Matches the file store's semantics
  * (write the whole blob atomically on every mutation) — Postgres gives us
  * crash-safety + concurrent-reader visibility for free.
  *
  * All tables in this control-plane share the `slkstate_` prefix so they
  * coexist cleanly with DuckLake's `__ducklake_*` tables in the same
  * Postgres database (the global `defaultMetastore` connection). */
final class PostgresStateStore(
    jdbcUrl: String,
    user: String,
    password: String,
    tableName: String = "slkstate_pool_state"
) extends StateStore:

  import StateStore.given

  // Force the driver to register; in some classloader topologies (e.g. assembly
  // jar + custom launcher) DriverManager skips the JAR's service-loader entry.
  Class.forName("org.postgresql.Driver")

  // Quote the identifier defensively in case of a non-default tableName, but
  // not interpolated into DDL/DML to keep statements analyzable.
  private val table = s"\"${tableName.replace("\"", "\"\"")}\""

  private def withConn[A](f: Connection => A): A =
    val c = DriverManager.getConnection(jdbcUrl, user, password)
    try f(c) finally c.close()

  private def ensureTable(c: Connection): Unit =
    val st = c.createStatement()
    try
      st.execute(
        s"""CREATE TABLE IF NOT EXISTS $table (
           |  id         INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
           |  content    JSONB NOT NULL,
           |  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
           |)""".stripMargin
      )
    finally st.close()

  def load(): StoredState = withConn { c =>
    ensureTable(c)
    val ps = c.prepareStatement(s"SELECT content::text FROM $table WHERE id = 1")
    try
      val rs = ps.executeQuery()
      try
        if rs.next() then
          decode[StoredState](rs.getString(1)).fold(
            err => throw new RuntimeException(s"failed to parse state from postgres: $err"),
            identity
          )
        else StoredState(Map.empty, Map.empty)
      finally rs.close()
    finally ps.close()
  }

  def save(state: StoredState): Unit = withConn { c =>
    ensureTable(c)
    val json = state.asJson.noSpaces
    val ps = c.prepareStatement(
      s"""INSERT INTO $table (id, content, updated_at) VALUES (1, ?::jsonb, NOW())
         |ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, updated_at = NOW()""".stripMargin
    )
    try
      ps.setString(1, json)
      ps.executeUpdate()
    finally ps.close()
  }

object PostgresStateStore:

  /** Build a store from the global `defaultMetastore` map. Throws on missing
    * required keys so the manager fails fast at startup rather than at the
    * first save. */
  def fromDefaultMetastore(meta: Map[String, String]): PostgresStateStore =
    def required(k: String) =
      meta.get(k).filter(_.nonEmpty).getOrElse(
        sys.error(s"defaultMetastore.$k must be set when stateStorage=postgres")
      )
    val host = required("pgHost")
    val port = required("pgPort")
    val user = required("pgUser")
    val pass = required("pgPassword")
    val db   = required("dbName")
    val url  = s"jdbc:postgresql://$host:$port/$db"
    new PostgresStateStore(url, user, pass)