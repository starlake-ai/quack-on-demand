package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.model.Tenant
import com.typesafe.scalalogging.LazyLogging

/** The secret references changeset 0048 parked. A `clientSecretRef` stored before 0048 has no
  * recorded author, and a reference names a secret of the manager's own environment, which only a
  * superuser may point a tenant at, so the changeset moved each one to [[ParkedKey]]. Nothing reads
  * that key, so the tenant's SSO gets no secret from it. The first `tenant/setAuth` write whose
  * result holds a secret source (the reference restored by a superuser, or the tenant's own client
  * secret) drops the key, and with it this report; until then every boot logs one ERROR per tenant
  * holding it.
  */
object ParkedSecretRefs extends LazyLogging:

  val ParkedKey: String = "clientSecretRefUnconfirmed"

  /** The prefixes of a secret reference, the vocabulary of
    * [[ai.starlake.quack.secrets.SecretRefResolver]]. A parked value without one of them is not a
    * reference (it may be a literal secret typed into the wrong key), so it is never printed.
    */
  val ReferencePrefixes: List[String] = List("env:", "aws-sm:", "gcp-sm:", "azure-kv:", "vault:")

  /** Printed in place of a parked value that is not a secret reference. */
  val NotAReference: String = "<not a secret reference, not shown>"

  /** How many code points of a stored value outside [[CommandSafe]] the line shows. */
  val ShownLimit: Int = 40

  /** The characters [[printable]] hides: ISO controls, Unicode format characters (the bidi
    * overrides and isolates among them) and the line and paragraph separators.
    */
  private val Hidden: Set[Int] =
    Set(
      Character.CONTROL,
      Character.FORMAT,
      Character.LINE_SEPARATOR,
      Character.PARAGRAPH_SEPARATOR
    ).map(_.toInt)

  /** What a stored reference must match, whole, to be put into the restore command: a word that
    * needs no quoting in any shell. Nothing stored is ever quoted or escaped, because POSIX shells,
    * fish, cmd.exe and PowerShell all parse quotes and escapes differently.
    */
  private val CommandSafe: String = "[A-Za-z0-9_:./=-]+"

  private val ReplacesMap: String =
    "repeat every other key of the tenant's auth config (scopes, baseUrl, realm, tenantId, " +
      "...): the save replaces the whole map."

  /** The advice for a reference the command carries. */
  private val ConfirmAdvice: String =
    s"Confirm the reference is meant for this tenant and $ReplacesMap"

  /** The advice for a reference outside [[CommandSafe]]: the command shows `...` for it. */
  private val TypeByHandAdvice: String =
    "The stored value, shown sanitized earlier in this line (control and format characters " +
      "print as ?), holds characters outside [A-Za-z0-9_:./=-], so the command at the end of " +
      "the line does not carry it: check that it is meant for this tenant and type it by hand, " +
      s"and $ReplacesMap"

  /** The advice for a value that is not a reference: there is nothing to confirm or restore. */
  private val NotAReferenceAdvice: String =
    "The stored value is not a secret reference, so there is nothing to confirm: a superuser " +
      "sets a secret source for the tenant (a client secret or a reference) and must " +
      ReplacesMap

  /** One message per tenant holding [[ParkedKey]], in the order given: the tenant, the parked
    * reference, the advice and, last on the line, the restore command, so selecting from `qod` to
    * the end yields only the command. What a stored value can do to the line is bounded: it is
    * shown only with a [[ReferencePrefixes known prefix]] (else [[NotAReference]]); its control,
    * format and separator characters print as `?`; outside `[A-Za-z0-9_:./=-]+` it is cut to
    * [[ShownLimit]] code points (then `...(+N more)`) with each backslash and quote escaped, so it
    * can neither carry a complete restore command nor close its own quotes; and it goes into the
    * command only when it matches `[A-Za-z0-9_:./=-]+` as a whole, a word that needs no quoting in
    * any shell. Otherwise the command shows `clientSecretRef=...` and the advice says to check the
    * stored value and type it by hand.
    */
  def messages(tenants: List[Tenant]): List[String] =
    tenants.flatMap { t =>
      t.authConfig.get(ParkedKey).map { raw =>
        val isReference = ReferencePrefixes.exists(p => raw.startsWith(p))
        val inCommand   = isReference && raw.matches(CommandSafe)
        val shown       = if isReference then display(raw) else NotAReference
        val refArg      = if inCommand then s"clientSecretRef=$raw" else "clientSecretRef=..."
        val advice      =
          if inCommand then ConfirmAdvice
          else if isReference then TypeByHandAdvice
          else NotAReferenceAdvice
        s"tenant '${t.id}': clientSecretRef $shown was stored before changeset 0048, which " +
          "parked it because its author is not recorded; nothing reads it, so the tenant's SSO " +
          s"gets no client secret from it. $advice Then a superuser runs: " +
          s"qod tenant set-auth ${t.id} --auth-provider ${t.authProvider} " +
          s"--auth-config issuerUrl=... --auth-config clientId=... --auth-config $refArg"
      }
    }

  /** Logs every [[messages]] line at ERROR: the root log level defaults to ERROR (`QOD_LOG_LEVEL`),
    * and operator-facing misconfiguration is logged at ERROR so it survives that quiet default.
    */
  def report(tenants: List[Tenant]): Unit = messages(tenants).foreach(m => logger.error(m))

  /** `raw` as the line shows it, in single quotes: sanitized by [[printable]]; a value outside
    * [[CommandSafe]] is cut to [[ShownLimit]] code points (then `...(+N more)` for the N left out)
    * and has each backslash and quote escaped (`\\` and `\'`), so it can neither close the quotes
    * around it nor escape the closing one. The limit keeps a stored value from carrying a complete
    * restore command into the line.
    */
  private def display(raw: String): String =
    val clean = printable(raw)
    if raw.matches(CommandSafe) then s"'$clean'"
    else
      val total   = clean.codePointCount(0, clean.length)
      val head    = clean.substring(0, clean.offsetByCodePoints(0, math.min(total, ShownLimit)))
      val more    = if total > ShownLimit then s"...(+${total - ShownLimit} more)" else ""
      val escaped = head.replace("\\", "\\\\").replace("'", "\\'")
      s"'$escaped$more'"

  /** `raw` with every [[Hidden]] character replaced by `?`, one `?` per code point. */
  private def printable(raw: String): String =
    raw
      .codePoints()
      .toArray
      .map { cp =>
        if Hidden.contains(Character.getType(cp)) then "?" else new String(Character.toChars(cp))
      }
      .mkString
