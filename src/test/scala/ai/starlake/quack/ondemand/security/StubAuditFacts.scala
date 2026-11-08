package ai.starlake.quack.ondemand.security

/** A control plane with nothing to report, unless a field says otherwise. */
final case class StubAuditFacts(
    superuserLogin: Boolean = false,
    permissive: List[(String, Int)] = Nil,
    dormantPats: Int = 0,
    writableSources: List[String] = Nil,
    noTtlBranches: List[String] = Nil,
    unboundServers: List[String] = Nil,
    unencrypted: List[(String, Int)] = Nil,
    local: List[String] = Nil,
    memory: List[String] = Nil,
    untrusted: List[String] = Nil,
    oidc: List[String] = Nil,
    failWith: Option[Throwable] = None
) extends AuditFacts:
  private def give[A](a: A): A                      = failWith.fold(a)(e => throw e)
  def controlPlaneLoginIsSuperuser(): Boolean       = give(superuserLogin)
  def permissiveBuiltinUsers(): List[(String, Int)] = give(permissive)
  def dormantPatsOfDisabledUsers(): Int             = give(dormantPats)
  def writableSourcesWithSecrets(): List[String]    = give(writableSources)
  def liveBranchesWithoutTtl(): List[String]        = give(noTtlBranches)
  def unboundApprovedServers(): List[String]        = give(unboundServers)
  def unencryptedDatabases(): List[(String, Int)]   = give(unencrypted)
  def localDatabases(): List[String]                = give(local)
  def memoryDatabases(): List[String]               = give(memory)
  def untrustedInitSql(): List[String]              = give(untrusted)
  def oidcTenants(): List[String]                   = give(oidc)
