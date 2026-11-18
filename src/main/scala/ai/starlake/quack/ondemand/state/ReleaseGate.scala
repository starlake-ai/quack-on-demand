package ai.starlake.quack.ondemand.state

import io.circe.Decoder
import io.circe.parser.decode

import java.sql.{Connection, DriverManager}
import scala.util.Using

/** The upgrade paths and rollback rules of every release (issues #221, #222), read from the bundled
  * `release/upgrades.json`, which `scripts/release/release_manifest.py` also copies into each
  * release's `release.json`.
  */
object ReleasePolicy:

  /** A release number. A suffix (`-SNAPSHOT`, `-rc1`) is dropped: a snapshot build is judged as the
    * release it leads to, so it can run on the schema it migrated itself.
    */
  final case class Version(major: Int, minor: Int, patch: Int) extends Ordered[Version]:
    def compare(that: Version): Int =
      summon[Ordering[(Int, Int, Int)]]
        .compare((major, minor, patch), (that.major, that.minor, that.patch))
    override def toString: String = s"$major.$minor.$patch"

  object Version:
    private val Pattern = raw"(\d+)\.(\d+)\.(\d+)(?:[-+].*)?".r

    def parse(text: String): Option[Version] = text.trim match
      case Pattern(a, b, c) => Some(Version(a.toInt, b.toInt, c.toInt))
      case _                => None

  final case class Release(
      version: Version,
      minFromVersion: Version,
      rollbackWithoutRestore: Boolean,
      rollbackBlockedBy: List[String],
      notes: String
  )

  private given Decoder[Version] =
    Decoder.decodeString.emap(s => Version.parse(s).toRight(s"not a release number: $s"))

  private given Decoder[Release] = Decoder.forProduct5(
    "version",
    "min_from_version",
    "rollback_without_restore",
    "rollback_blocked_by",
    "notes"
  )(Release.apply)

  private final case class File(releases: List[Release])
  private given Decoder[File] = Decoder.forProduct1("releases")(File.apply)

  final case class Policy(releases: List[Release]):
    private val sorted = releases.sortBy(_.version)

    /** The entry that speaks for `build`: its own, else the newest one before it (a snapshot whose
      * entry is not written yet).
      */
    def entryFor(build: Version): Option[Release] = sorted.takeWhile(_.version <= build).lastOption

    /** The oldest release that can run on the schema `build` leaves: the newest release up to
      * `build` whose migrations are one way, else the oldest release listed.
      */
    def schemaFloor(build: Version): Option[Version] =
      val upTo = sorted.takeWhile(_.version <= build)
      upTo.reverse.find(!_.rollbackWithoutRestore).orElse(upTo.headOption).map(_.version)

  val ResourcePath = "release/upgrades.json"

  /** The bundled policy. A missing or malformed file is a build defect: it fails the boot. */
  def bundled(): Policy =
    val text = Option(getClass.getClassLoader.getResourceAsStream(ResourcePath))
      .map(in => Using.resource(in)(i => new String(i.readAllBytes(), "UTF-8")))
      .getOrElse(sys.error(s"$ResourcePath is missing from the jar"))
    parse(text).fold(e => sys.error(s"$ResourcePath: $e"), identity)

  def parse(text: String): Either[String, Policy] =
    decode[File](text).left.map(_.getMessage).map(f => Policy(f.releases))

/** The boot's release gate (issues #221, #222), run around the core Liquibase apply in `Main`.
  *
  * Before Liquibase it reads two rows of `qodstate_release` (Liquibase `0059`) and refuses to start
  * when this build cannot run here: `schema_floor`, written by a newer release whose migrations are
  * one way, is above this build (the refusal names the changesets this build does not know), or
  * `last_version`, the last release that applied the schema, is older than this release's
  * `min_from_version`. After Liquibase it records this build as `last_version` and raises
  * `schema_floor` to its own floor.
  *
  * Only builds that carry the gate are protected: a release older than the one that added it never
  * reads the rows. A control plane with no rows (a fresh one, or one last run by a release before
  * the gate) is not refused. A build with no release number (`sbt run`, the test classpath) is
  * neither gated nor recorded.
  */
final class ReleaseGate(
    jdbcUrl: String,
    user: String,
    password: String,
    build: Option[ReleasePolicy.Version],
    policy: ReleasePolicy.Policy
):
  import ReleasePolicy.Version

  private def connect(): Connection = DriverManager.getConnection(jdbcUrl, user, password)

  /** Before Liquibase: `Left` is the refusal to print; `Right` holds notes to print (stderr). */
  def check(): Either[String, List[String]] = build match
    case None        => Right(Nil)
    case Some(build) =>
      val (stored, unknown) = Using.resource(connect()) { c =>
        (ReleaseGate.readRows(c), ReleaseGate.unknownChangesets(c))
      }
      ReleaseGate.decide(build, stored, unknown, policy)

  /** After Liquibase: record this build. */
  def record(): Unit = build.foreach { build =>
    Using.resource(connect()) { c =>
      val stored = ReleaseGate.readRows(c)
      val floor  = (stored.schemaFloor.toList ++ policy.schemaFloor(build).toList).maxOption
      ReleaseGate.upsert(c, ReleaseGate.LastVersion, build.toString)
      floor.foreach(f => ReleaseGate.upsert(c, ReleaseGate.SchemaFloor, f.toString))
    }
  }

object ReleaseGate:
  import ReleasePolicy.{Policy, Version}

  val LastVersion = "last_version"
  val SchemaFloor = "schema_floor"

  /** What `qodstate_release` holds; both empty when the table or a row is missing. */
  final case class Stored(lastVersion: Option[Version], schemaFloor: Option[Version])

  /** The gate for the control plane `Main` applies Liquibase to, judging this jar's own release. */
  def fromDefaultMetastore(meta: Map[String, String]): ReleaseGate =
    def required(k: String): String =
      meta.get(k).filter(_.nonEmpty).getOrElse(sys.error(s"defaultMetastore.$k must be set"))
    new ReleaseGate(
      s"jdbc:postgresql://${required("pgHost")}:${required("pgPort")}/${required("dbName")}",
      required("pgUser"),
      required("pgPassword"),
      Option(classOf[ReleaseGate].getPackage.getImplementationVersion).flatMap(Version.parse),
      ReleasePolicy.bundled()
    )

  /** The pure decision. `unknown`: ids of changesets applied here that this build does not know. */
  def decide(
      build: Version,
      stored: Stored,
      unknown: List[String],
      policy: Policy
  ): Either[String, List[String]] =
    val last      = stored.lastVersion.fold("a release this build cannot name")(v => s"qod $v")
    val unknownIs =
      if unknown.isEmpty then ""
      else s" It holds Liquibase changesets this build does not know: ${unknown.mkString(", ")}."
    stored.schemaFloor.filter(_ > build) match
      case Some(floor) =>
        Left(
          s"refusing to start: this control plane was last migrated by $last, and only qod " +
            s"$floor or later can run on its schema, as a release since $build changed it one " +
            s"way.$unknownIs Run qod $floor or later, or restore the control-plane database " +
            "backup taken before the upgrade, then start this release."
        )
      case None =>
        val entry = policy.entryFor(build)
        (entry, stored.lastVersion) match
          case (Some(e), Some(from)) if from < e.minFromVersion =>
            Left(
              s"refusing to start: qod $build upgrades directly only from qod " +
                s"${e.minFromVersion} or later, and this control plane was last run by qod " +
                s"$from. Upgrade to qod ${e.minFromVersion} first, following its upgrade notes, " +
                s"then to $build (${e.notes})."
            )
          case _ =>
            Right(
              if unknown.isEmpty then Nil
              else
                List(
                  s"WARN: this control plane holds Liquibase changesets qod $build does not " +
                    s"know (${unknown.mkString(", ")}), applied by a newer release (last run: " +
                    s"$last), which recorded that qod $build can still run on its schema."
                )
            )

  private[state] def readRows(c: Connection): Stored =
    if !tableExists(c, "qodstate_release") then Stored(None, None)
    else
      Using.resource(c.createStatement()) { st =>
        Using.resource(st.executeQuery("SELECT name, value FROM qodstate_release")) { rs =>
          val rows = Iterator
            .continually(rs.next())
            .takeWhile(identity)
            .map(_ => rs.getString(1) -> rs.getString(2))
            .toMap
          Stored(
            rows.get(LastVersion).flatMap(Version.parse),
            rows.get(SchemaFloor).flatMap(Version.parse)
          )
        }
      }

  /** Ids of changesets in `DATABASECHANGELOG` that the core changelog does not list. Only explains
    * a refusal: the decision reads `qodstate_release`. A module's changesets (another file) are
    * left out.
    */
  private[state] def unknownChangesets(c: Connection): List[String] =
    if !tableExists(c, "databasechangelog") then Nil
    else
      val known = coreChangesetIds()
      Using.resource(c.createStatement()) { st =>
        Using.resource(
          st.executeQuery(
            "SELECT id, filename FROM databasechangelog ORDER BY orderexecuted"
          )
        ) { rs =>
          Iterator
            .continually(rs.next())
            .takeWhile(identity)
            .map(_ => (rs.getString(1), rs.getString(2)))
            .toList
            .collect {
              case (id, file) if file.startsWith(CoreChangelogDir) && !known(id) => id
            }
        }
      }

  private val CoreChangelogDir = "db/changelog/"

  /** The ids the bundled core changelog defines: `id:` lines of each included file. */
  private def coreChangesetIds(): Set[String] =
    val loader                     = getClass.getClassLoader
    def read(path: String): String =
      Option(loader.getResourceAsStream(path))
        .map(in => Using.resource(in)(i => new String(i.readAllBytes(), "UTF-8")))
        .getOrElse("")
    val includes = raw"(?m)^\s*file:\s*(\S+)\s*$$".r
    val ids      = raw"(?m)^\s*id:\s*(\S+)\s*$$".r
    includes
      .findAllMatchIn(read(s"${CoreChangelogDir}db.changelog-master.yaml"))
      .map(_.group(1))
      .flatMap(f => ids.findAllMatchIn(read(f)).map(_.group(1)))
      .toSet

  private def tableExists(c: Connection, table: String): Boolean =
    Using.resource(c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) { ps =>
      ps.setString(1, table)
      Using.resource(ps.executeQuery())(rs => rs.next() && rs.getBoolean(1))
    }

  private def upsert(c: Connection, name: String, value: String): Unit =
    Using.resource(
      c.prepareStatement(
        "INSERT INTO qodstate_release (name, value) VALUES (?, ?) " +
          "ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()"
      )
    ) { ps =>
      ps.setString(1, name)
      ps.setString(2, value)
      ps.executeUpdate()
      ()
    }
