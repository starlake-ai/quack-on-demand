package ai.starlake.quack.ondemand.runtime

import scala.sys.process.*
import scala.util.Try

/** The PowerShell that runs scripts/spawn-quack-node.ps1 in tests: Windows PowerShell on Windows
  * (what LocalQuackBackend.defaultCommand runs), else pwsh when installed. None cancels the
  * PowerShell twins.
  */
object PowerShell:
  lazy val command: Option[Seq[String]] =
    val candidates = if scala.util.Properties.isWin then Seq("powershell.exe") else Seq("pwsh")
    candidates
      .find(c => Try(Process(Seq(c, "-NoProfile", "-Command", "1")).!!).isSuccess)
      .map(c => Seq(c, "-NoProfile"))
