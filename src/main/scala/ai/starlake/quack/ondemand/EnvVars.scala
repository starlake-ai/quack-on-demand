package ai.starlake.quack.ondemand

object EnvVars:
  def apiKey: Option[String] = sys.env.get("SL_QUACK_API_KEY")