package ai.starlake.quack.ondemand.fleet

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NodePgAddressSpec extends AnyFlatSpec with Matchers:
  private val managed = Map("pgHost" -> "postgres", "pgPort" -> "5432", "pgPassword" -> "pw")

  "rewrite" should "leave the map unchanged when no node address is configured" in {
    NodePgAddress.rewrite(managed, "postgres", "5432", "", "") shouldBe managed
  }
  it should "rewrite host and port of the managed Postgres" in {
    NodePgAddress.rewrite(managed, "postgres", "5432", "host.docker.internal", "15432") shouldBe
      managed ++ Map("pgHost" -> "host.docker.internal", "pgPort" -> "15432")
  }
  it should "rewrite only the host when only the host is configured" in {
    NodePgAddress.rewrite(managed, "postgres", "5432", "10.0.0.5", "") shouldBe
      managed.updated("pgHost", "10.0.0.5")
  }
  it should "read the managed host and port as the rule on QoD's login reads them" in {
    val spelled = Map("pgHost" -> "POSTGRES.", "pgPort" -> "05432", "pgPassword" -> "pw")
    NodePgAddress.rewrite(spelled, "postgres", "5432", "10.0.0.5", "15432") shouldBe
      spelled ++ Map("pgHost" -> "10.0.0.5", "pgPort" -> "15432")
  }
  it should "never rewrite a row on another port of the managed host" in {
    val custom = managed.updated("pgPort", "6543")
    NodePgAddress.rewrite(custom, "postgres", "5432", "10.0.0.5", "15432") shouldBe custom
    NodePgAddress.rewrite(custom, "postgres", "5432", "10.0.0.5", "") shouldBe custom
  }
  it should "read a default with no port as the spawn scripts do" in {
    NodePgAddress.rewrite(managed, "postgres", "", "10.0.0.5", "") shouldBe
      managed.updated("pgHost", "10.0.0.5")
    val custom = managed.updated("pgPort", "6543")
    NodePgAddress.rewrite(custom, "postgres", "", "10.0.0.5", "") shouldBe custom
  }
  it should "pass a database on its own Postgres through untouched" in {
    val own = managed.updated("pgHost", "pg.customer.example")
    NodePgAddress.rewrite(own, "postgres", "5432", "10.0.0.5", "15432") shouldBe own
  }
  it should "never treat an empty default host as a match" in {
    val blank = managed.updated("pgHost", "")
    NodePgAddress.rewrite(blank, "", "5432", "10.0.0.5", "") shouldBe blank
  }
