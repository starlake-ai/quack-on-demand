package ai.starlake.quack.ondemand.runtime

import java.nio.file.{Files, Path, Paths}

/** The real qod-node for tests that start a live node through the spawn scripts: `QOD_NODE_BIN`,
  * else the development build of this checkout (`cmake -S native/qod-node -B
  * native/qod-node/build`). None when neither exists; those tests then report CANCELED via
  * `assume`, as they do without a DuckDB CLI.
  */
object QodNodeTestBinary:

  private val exe =
    if System.getProperty("os.name").toLowerCase.contains("win") then "qod-node.exe" else "qod-node"

  lazy val path: Option[Path] =
    sys.env
      .get("QOD_NODE_BIN")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .orElse(
        Seq(
          Paths.get("native/qod-node/build", exe),
          Paths.get("native/qod-node/build/Release", exe)
        ).find(Files.isRegularFile(_))
      )
      .map(_.toAbsolutePath)
      .filter(Files.isExecutable(_))
