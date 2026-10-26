package ai.starlake.quack.edge.authz

import java.util.Locale

/** What a non-superuser may do with a setting (spec decision 11). */
enum SettingClass:
  /** Changing it for one session reaches no credential, file, I/O endpoint, resource or other
    * session: allowed at session or local scope, never at global scope.
    */
  case Session

  /** Superusers only. */
  case Superuser

object SettingClass:
  /** The class a table cell names: `session` or `superuser`. */
  def parse(cell: String): Option[SettingClass] =
    SettingClass.values.find(_.toString.toLowerCase(Locale.ROOT) == cell)

/** The class of every setting and core alias a node knows, generated from qod-node's own listing
  * (`native/qod-node/settings.json`) into the bundled `authz/setting-classes.tsv` by
  * scripts/dev/gen-qod-node-classification.sh. An alias has its target's class. SettingClassesSpec
  * fails when the table and the listing differ. A setting the table lacks has no class, and the
  * decision pipeline refuses it.
  */
object SettingClasses:

  val Resource: String = "authz/setting-classes.tsv"

  /** Every classified setting and alias, lowercased. Read on first use; throws when the bundled
    * table is missing or malformed, which SettingClassesSpec rules out.
    */
  lazy val all: Map[String, SettingClass] = parse(ClassificationTables.read(Resource))

  /** The class of setting (or alias) `name`, case-insensitively, or None. */
  def of(name: String): Option[SettingClass] = all.get(name.toLowerCase(Locale.ROOT))

  /** The rows (`name`, `class`) of `text`. Throws IllegalArgumentException naming the line of an
    * unknown class or a setting classified twice.
    */
  private[authz] def parse(text: String): Map[String, SettingClass] =
    ClassificationTables.rows(text, columns = 2).foldLeft(Map.empty[String, SettingClass]) {
      case (acc, (line, cells)) =>
        SettingClass.parse(cells(1)) match
          case None =>
            throw new IllegalArgumentException(s"line $line: unknown class ${cells(1)}")
          case Some(_) if acc.contains(cells(0)) =>
            throw new IllegalArgumentException(s"line $line: ${cells(0)} is classified twice")
          case Some(cls) => acc + (cells(0) -> cls)
    }
