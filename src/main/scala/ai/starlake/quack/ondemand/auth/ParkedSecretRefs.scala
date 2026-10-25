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

  /** The characters a shell word may hold without quoting. */
  private val ShellSafe: String = "[A-Za-z0-9_:./=-]+"

  /** One message per tenant holding [[ParkedKey]], in the order given: the tenant, the parked
    * reference and the restore step. A stored value cannot shape the log or the command: it prints
    * only with a [[ReferencePrefixes known prefix]] (else [[NotAReference]], and the restore step
    * carries `...` for it), its control, format and separator characters print as `?`, and the
    * `clientSecretRef=` argument of the restore command is POSIX-quoted unless it holds only
    * `[A-Za-z0-9_:./=-]`.
    */
  def messages(tenants: List[Tenant]): List[String] =
    tenants.flatMap { t =>
      t.authConfig.get(ParkedKey).map { raw =>
        val ref    = Option.when(ReferencePrefixes.exists(p => raw.startsWith(p)))(printable(raw))
        val shown  = ref.fold(NotAReference)(r => s"'$r'")
        val refArg = ref.fold("clientSecretRef=...")(r => shellWord(s"clientSecretRef=$r"))
        s"tenant '${t.id}': clientSecretRef $shown was stored before changeset 0048, which " +
          "parked it because its author is not recorded; nothing reads it, so the tenant's SSO " +
          "gets no client secret from it. Confirm the reference is meant for this tenant, then " +
          s"a superuser runs: qod tenant set-auth ${t.id} --auth-provider ${t.authProvider} " +
          s"--auth-config issuerUrl=... --auth-config clientId=... --auth-config $refArg; " +
          "repeat every other key of the tenant's auth config (scopes, baseUrl, realm, " +
          "tenantId, ...): the save replaces the whole map"
      }
    }

  /** Logs every [[messages]] line at ERROR: the root log level defaults to ERROR (`QOD_LOG_LEVEL`),
    * and operator-facing misconfiguration is logged at ERROR so it survives that quiet default.
    */
  def report(tenants: List[Tenant]): Unit = messages(tenants).foreach(m => logger.error(m))

  /** `raw` with every [[Hidden]] character replaced by `?`, one `?` per code point. */
  private def printable(raw: String): String =
    raw
      .codePoints()
      .toArray
      .map { cp =>
        if Hidden.contains(Character.getType(cp)) then "?" else new String(Character.toChars(cp))
      }
      .mkString

  /** `arg` as one POSIX shell word: unchanged when it holds only `[A-Za-z0-9_:./=-]`, otherwise in
    * single quotes with each `'` written as `'\''`, so a stored value can add no command, expansion
    * or redirection to a pasted line.
    */
  private def shellWord(arg: String): String =
    if arg.matches(ShellSafe) then arg else "'" + arg.replace("'", "'\\''") + "'"
