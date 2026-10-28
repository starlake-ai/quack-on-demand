package ai.starlake.quack.ondemand.initsql

import ai.starlake.quack.edge.authz.{SettingClass, SettingClasses}
import io.circe.JsonObject
import io.circe.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.util.Locale

/** The settings a tenant admin's initSql may set (qod-node's kInitSettings, from
  * native/qod-node/init-settings.txt) against the setting classification
  * (authz/setting-classes.tsv), the parse screen's reserved settings and the path-valued settings.
  *
  * A setting classed `session` is one decision 11 reviewed as harmless for one session. A setting
  * classed `superuser` is admitted only when it is in [[GlobalResourceSettings]] and DuckDB cannot
  * set it for one session at all (its superuser class then says "global", not "dangerous"): the
  * process's memory, threads, temp space and checkpointing, which a node dedicated to one tenant's
  * pool spends on that tenant alone. Any other change of class fails here, so a setting that
  * becomes dangerous drops out of the list by review, never silently.
  */
class InitSettingsAllowlistSpec extends AnyFlatSpec with Matchers:

  private def lower(s: String): String = s.toLowerCase(Locale.ROOT)

  private def lines(path: String): List[String] =
    Files.readString(Paths.get(path)).linesIterator.map(_.trim).filter(_.nonEmpty).toList

  private val InitSettings = lines("native/qod-node/init-settings.txt")

  /** Classed superuser only because no session can set them: reviewed one by one. */
  private val GlobalResourceSettings = Set(
    "checkpoint_threshold",
    "max_memory",
    "max_temp_directory_size",
    "memory_limit",
    "threads",
    "wal_autocheckpoint",
    "worker_threads"
  )

  private lazy val listed: List[JsonObject] =
    parser
      .parse(Files.readString(Paths.get("native/qod-node/settings.json")))
      .fold(e => fail(s"settings.json: ${e.getMessage}"), identity)
      .asArray
      .getOrElse(fail("settings.json is not an array"))
      .toList
      .map(_.asObject.getOrElse(fail("settings.json holds a non-object")))

  private def str(o: JsonObject, k: String): Option[String] = o(k).flatMap(_.asString)

  /** The settings.json row of `name`, through its alias. */
  private def row(name: String): JsonObject =
    val byName = listed.map(o => lower(str(o, "name").getOrElse("")) -> o).toMap
    val first  = byName.getOrElse(name, fail(s"settings.json lacks $name"))
    if str(first, "kind").contains("alias") then
      byName.getOrElse(lower(str(first, "target").get), fail(s"$name names a missing target"))
    else first

  /** Mirror of classify-qod-node.py's session_capable: whether DuckDB lets one session set it. */
  private def sessionCapable(o: JsonObject): Boolean =
    val scope   = str(o, "scope").getOrElse("")
    val setters = str(o, "setters").getOrElse("")
    if str(o, "kind").contains("core") then
      Set("local_only", "local_default", "global_default").contains(scope) ||
      (scope == "none" && setters.endsWith("l"))
    else setters.endsWith("l")

  /** The parse screen's reserved settings (screen_rules.cpp kReservedSettings) and prefixes. */
  private lazy val reserved: Set[String] =
    val cpp   = Files.readString(Paths.get("native/qod-node/src/screen_rules.cpp"))
    val start = cpp.indexOf("kReservedSettings[] = {")
    start should be >= 0
    val body = cpp.substring(start, cpp.indexOf("};", start))
    "\"([a-z0-9_]+)\"".r.findAllMatchIn(body).map(_.group(1)).toSet

  "init-settings.txt" should "be sorted, lowercase and without duplicates" in {
    InitSettings shouldBe InitSettings.sorted
    InitSettings.distinct shouldBe InitSettings
    InitSettings.foreach(n => n shouldBe lower(n))
  }

  it should "hold only classified settings: session ones, or reviewed global resource ones" in {
    InitSettings.foreach { name =>
      withClue(s"$name: ") {
        SettingClasses.of(name) match
          case None                         => fail("not in setting-classes.tsv")
          case Some(SettingClass.Session)   => succeed
          case Some(SettingClass.Superuser) =>
            withClue("classed superuser and not a reviewed global resource setting: ")(
              GlobalResourceSettings should contain(name)
            )
            withClue("classed superuser although a session can set it: ")(
              sessionCapable(row(name)) shouldBe false
            )
      }
    }
    // Every reviewed global setting is still global: a release that makes one session-settable has
    // classed it superuser for another reason.
    GlobalResourceSettings.foreach(n => withClue(n)(sessionCapable(row(n)) shouldBe false))
  }

  it should "never name a setting the parse screen reserves, nor a path-valued one" in {
    reserved should contain("secret_directory")
    InitSettings.foreach { name =>
      withClue(name)(reserved should not contain name)
      withClue(name)(
        name.startsWith("s3_") || name.startsWith("external_file_cache_") shouldBe false
      )
      // A name about a path holds one of these words; a size of one (max_temp_directory_size) is a
      // number.
      val pathLike = List("directory", "path", "file", "home", "_dir").exists(name.contains)
      withClue(name)(pathLike && !name.endsWith("_size") shouldBe false)
    }
    InitSettings should contain noneOf (
      "temp_directory",
      "secret_directory",
      "extension_directory",
      "home_directory"
    )
  }

  "init-extensions.txt" should "be sorted, lowercase, without duplicates, and name no remote service" in {
    val exts = lines("native/qod-node/init-extensions.txt")
    exts shouldBe exts.sorted
    exts.distinct shouldBe exts
    // A service with its own credentials, a web server, host ODBC drivers: never by a tenant.
    exts should contain noneOf ("motherduck", "ui", "odbc_scanner", "quack")
  }
