import sbt._
import sbt.Keys._

import scala.sys.process._

object UiBuild {
  val uiBuild = taskKey[Unit]("Build the React UI into src/main/resources/ui")

  lazy val settings: Seq[Setting[_]] = Seq(
    uiBuild := {
      val log = streams.value.log
      val base = baseDirectory.value
      val uiDir = base / "ui"
      // On Windows the npm launcher is `npm.cmd`; scala.sys.process resolves
      // executables via CreateProcess, which won't find the bare `npm` shim.
      val npm =
        if (sys.props.getOrElse("os.name", "").toLowerCase.contains("win")) "npm.cmd" else "npm"
      if (!(uiDir / "node_modules").exists()) {
        log.info("ui: running npm ci")
        val rc = Process(npm :: "ci" :: Nil, uiDir).!
        if (rc != 0) sys.error("npm ci failed")
      }
      log.info("ui: running npm run build")
      val rc = Process(npm :: "run" :: "build" :: Nil, uiDir).!
      if (rc != 0) sys.error("npm run build failed")
    },
    Compile / resourceGenerators += Def.task {
      uiBuild.value
      val uiOut = (Compile / resourceDirectory).value / "ui"
      if (uiOut.exists()) ((uiOut ** "*").get.filter(_.isFile)) else Nil
    }.taskValue,
    Compile / compile := (Compile / compile).dependsOn(Compile / copyResources).value
  )
}