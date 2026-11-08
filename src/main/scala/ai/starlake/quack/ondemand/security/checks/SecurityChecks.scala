package ai.starlake.quack.ondemand.security.checks

import ai.starlake.quack.ondemand.security.SecurityCheck

object SecurityChecks:
  val all: List[SecurityCheck] =
    NetworkChecks.all ++ SecretChecks.all ++ IdentityChecks.all ++ NodeDataChecks.all ++ FleetChecks.all
