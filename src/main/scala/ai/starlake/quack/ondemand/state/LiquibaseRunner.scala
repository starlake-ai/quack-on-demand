package ai.starlake.quack.ondemand.state

import com.typesafe.scalalogging.LazyLogging
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor

import java.sql.{Connection, DriverManager}

/** Applies the YAML changelog under `db/changelog/db.changelog-master.yaml`
  * to the configured Postgres database. Liquibase tracks applied
  * changesets in its own `DATABASECHANGELOG` / `DATABASECHANGELOGLOCK`
  * tables, so a second `run` against an up-to-date DB is a no-op. */
final class LiquibaseRunner(
    jdbcUrl:       String,
    user:          String,
    password:      String,
    changelogPath: String = "db/changelog/db.changelog-master.yaml"
) extends LazyLogging:

  Class.forName("org.postgresql.Driver")

  def run(): Unit =
    val jdbc: Connection = DriverManager.getConnection(jdbcUrl, user, password)
    try
      val db = DatabaseFactory
        .getInstance
        .findCorrectDatabaseImplementation(new JdbcConnection(jdbc))
      val lb = new Liquibase(changelogPath, new ClassLoaderResourceAccessor, db)
      try
        logger.info(s"liquibase: applying $changelogPath against $jdbcUrl")
        lb.update(new liquibase.Contexts, new liquibase.LabelExpression)
        logger.info("liquibase: up to date")
      finally lb.close()
    finally jdbc.close()

object LiquibaseRunner:

  /** Build a runner from the global `defaultMetastore` map (pgHost,
    * pgPort, pgUser, pgPassword, dbName). Each key must be set. */
  def fromDefaultMetastore(meta: Map[String, String]): LiquibaseRunner =
    def required(k: String): String =
      meta.get(k).filter(_.nonEmpty).getOrElse(
        sys.error(s"defaultMetastore.$k must be set for LiquibaseRunner")
      )
    val host = required("pgHost")
    val port = required("pgPort")
    val user = required("pgUser")
    val pass = required("pgPassword")
    val db   = required("dbName")
    val url  = s"jdbc:postgresql://$host:$port/$db"
    new LiquibaseRunner(url, user, pass)
