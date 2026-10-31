package ai.starlake.quack.ondemand.runtime

import java.nio.file.{Files, Path, Paths}

/** One DuckDB extension directory shared by the live specs' throwaway HOMEs, so a run downloads
  * httpfs, avro, quack and a federation scanner once instead of at every node start: on a slow link
  * those downloads alone outlast a spec's start deadline. Only `~/.duckdb/extensions` is shared,
  * never the HOME itself, so a node still sees no stored secrets of anyone. DuckDB installs through
  * a uniquely named temporary file and a rename, so concurrent installs into it are safe.
  */
object LiveExtensionCache:

  lazy val dir: Path =
    Files.createDirectories(Paths.get("target/live-duckdb-extensions").toAbsolutePath)

  /** `home`, its `.duckdb/extensions` a link to [[dir]]. */
  def seed(home: Path): Path =
    val link = Files.createDirectories(home.resolve(".duckdb")).resolve("extensions")
    if !Files.exists(link) then Files.createSymbolicLink(link, dir)
    home
