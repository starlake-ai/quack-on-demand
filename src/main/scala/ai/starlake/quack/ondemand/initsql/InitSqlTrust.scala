package ai.starlake.quack.ondemand.initsql

/** Who may write what a node runs at start (spec Section 8, Task 20).
  *
  * A database's or a pool's `initSql` is trusted when a superuser or the manager itself wrote it
  * (the boot provisioning hook, a manifest import, a branch copying its parent): nodes run it as
  * written, with their full privileges. A tenant admin's `initSql` is untrusted: the spawn scripts
  * hand it to qod-node inside a marked section, and qod-node runs only the statements of its tenant
  * initSql allowlist (a `SET` / `RESET` of a listed setting to a literal, a read-only `PRAGMA`, an
  * `INSTALL` / `LOAD` of a listed extension), skipping every other one.
  */
object InitSqlTrust:

  /** The trust flag a write leaves on a row whose stored `initSql` is `stored` with flag
    * `storedTrusted`, when the write carries `incoming` (None: the write leaves `initSql` alone)
    * and its writer is a superuser or the manager (`writerTrusted`).
    *
    * A superuser's write that carries `initSql` marks it trusted, changed or not: that is how a
    * superuser vouches for a tenant admin's text. A tenant admin's write that changes it marks it
    * untrusted; one that resends it unchanged (an edit form resends every field) keeps the flag, so
    * a superuser's text stays trusted. An empty `initSql` holds nothing to judge and is trusted.
    */
  def afterWrite(
      stored: String,
      storedTrusted: Boolean,
      incoming: Option[String],
      writerTrusted: Boolean
  ): Boolean =
    incoming match
      case None                                         => storedTrusted
      case Some(sql) if sql.trim.isEmpty                => true
      case Some(sql) if sql == stored && !writerTrusted => storedTrusted
      case Some(_)                                      => writerTrusted

  /** The trust flag of a row a manifest import writes (only superusers import): the manifest's own
    * flag when it carries one (an exported row says who wrote it, so export then import trusts
    * nothing new); without one (a hand-written manifest, the superuser's own text) trusted, except
    * that an existing row whose SQL the manifest leaves unchanged keeps its stored flag. `existing`
    * is the stored row's (sql, flag).
    */
  def onImport(
      sql: String,
      declared: Option[Boolean],
      existing: Option[(String, Boolean)]
  ): Boolean =
    declared match
      case Some(flag) => flag
      case None       =>
        existing match
          case Some((stored, flag)) if stored == sql => flag
          case _                                     => true

  /** The trust flag of a newly created row. */
  def onCreate(sql: String, writerTrusted: Boolean): Boolean =
    afterWrite("", storedTrusted = true, Some(sql), writerTrusted)

  /** What the spawn scripts write around an untrusted section; any line holding it, in any case, is
    * removed from an `initSql` before the scripts mark it, so no text can end its own section.
    * Refused at write time for every writer ([[markerRefusal]]).
    */
  val MarkerText: String = "qod:untrusted-"

  /** Why `sql` may not be stored as an `initSql`: it holds [[MarkerText]], which the spawn scripts
    * reserve for the lines that mark a tenant section.
    */
  def markerRefusal(sql: String): Option[String] =
    Option.when(sql.toLowerCase(java.util.Locale.ROOT).contains(MarkerText))(
      s"initSql may not contain the text '$MarkerText': the node launchers reserve it for the " +
        "lines that mark a tenant section"
    )
