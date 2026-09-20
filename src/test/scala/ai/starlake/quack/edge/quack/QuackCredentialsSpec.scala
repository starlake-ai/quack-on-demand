package ai.starlake.quack.edge.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class QuackCredentialsSpec extends AnyFlatSpec with Matchers:

  "parse" should "read the basic form" in:
    QuackCredentials.parse("tenant=acme&pool=bi&user=alice&password=s3cret") shouldBe Right(
      QuackCredentials(Some("acme"), Some("bi"), Some(("alice", "s3cret")), None, false)
    )

  it should "percent-decode values so passwords may carry & and =" in:
    val r = QuackCredentials.parse("tenant=acme&pool=bi&user=a%40x.io&password=p%26q%3Dr%25")
    r.toOption.get.basic shouldBe Some(("a@x.io", "p&q=r%"))

  it should "pass a tenant id through untouched" in:
    QuackCredentials
      .parse("tenant=t-0a1b2c3d&pool=bi&user=u&password=p")
      .toOption
      .get
      .tenant shouldBe
      Some("t-0a1b2c3d")

  it should "read the superuser flag case-insensitively" in:
    QuackCredentials
      .parse("tenant=acme&pool=bi&user=u&password=p&superuser=TRUE")
      .toOption
      .get
      .superuser shouldBe true
    QuackCredentials
      .parse("tenant=acme&pool=bi&user=u&password=p&superuser=no")
      .toOption
      .get
      .superuser shouldBe false

  it should "read the bearer form" in:
    QuackCredentials.parse("tenant=acme&pool=bi&token=eyJ.abc.def") shouldBe Right(
      QuackCredentials(Some("acme"), Some("bi"), None, Some("eyJ.abc.def"), false)
    )

  it should "leave tenant and pool absent when not given (the handshake reports them)" in:
    QuackCredentials.parse("user=u&password=p") shouldBe Right(
      QuackCredentials(None, None, Some(("u", "p")), None, false)
    )

  it should "reject an empty string" in:
    QuackCredentials.parse("").swap.toOption.get should include("empty")

  it should "reject a token without password or bearer" in:
    QuackCredentials.parse("tenant=acme&pool=bi&user=u").swap.toOption.get should include(
      "password"
    )

  it should "reject both password and bearer" in:
    QuackCredentials.parse("user=u&password=p&token=t").swap.toOption.get should include("both")

  it should "reject a personal access token explicitly" in:
    QuackCredentials.parse("tenant=acme&pool=bi&pat=qod_pat_x").swap.toOption.get should include(
      "personal access tokens"
    )

  it should "reject unknown keys and bare words" in:
    QuackCredentials
      .parse("tenant=acme&pool=bi&user=u&password=p&color=red")
      .swap
      .toOption
      .get should include(
      "unknown"
    )
    QuackCredentials.parse("nope").swap.toOption.get should include("expected key=value")

  it should "reject a password without a user" in:
    QuackCredentials.parse("tenant=acme&pool=bi&password=p").swap.toOption.get should include(
      "user"
    )
