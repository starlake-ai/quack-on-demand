package ai.starlake.quack.model

final case class PoolKey(tenant: String, pool: String):
  override def toString: String = s"$tenant/$pool"

object PoolKey:
  def parse(s: String): Either[String, PoolKey] = s.split("/", 2) match
    case Array(t, p) if t.nonEmpty && p.nonEmpty => Right(PoolKey(t, p))
    case _ => Left(s"invalid pool key: '$s' (expected tenant/pool)")