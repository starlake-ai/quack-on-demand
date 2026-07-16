package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.DriverManager

class DemoPostgresSpec extends AnyFlatSpec with Matchers:

  "DemoPostgres" should "start, create a database, accept a connection, and stop" in {
    val dir = Files.createTempDirectory("demo-pg-spec")
    val pg  = DemoPostgres.start(dir)
    try
      pg.coords.host shouldBe "localhost"
      pg.coords.port should be > 0
      pg.createDatabase("qod_demo_probe")
      pg.createDatabase("qod_demo_probe") // idempotent second call must not throw
      val url  = s"jdbc:postgresql://${pg.coords.host}:${pg.coords.port}/qod_demo_probe"
      val conn = DriverManager.getConnection(url, pg.coords.user, pg.coords.password)
      try conn.isValid(2) shouldBe true
      finally conn.close()
    finally pg.stop()
  }
