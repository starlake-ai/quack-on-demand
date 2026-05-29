package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{PoolKey, Role, RoleDistribution, RunningNode}
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._

import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant

/** Persistence layer for tenant + pool definitions. Implementations atomically
  * read and write the whole [[StoredState]] blob - the supervisor calls
  * `save` on every mutation, so partial-write recovery is the implementation's
  * responsibility. */
trait StateStore:
  def load(): StoredState
  def save(state: StoredState): Unit

/** JSON file with atomic temp-file + rename for crash-safety. */
final class FileStateStore(path: Path) extends StateStore:

  import StateStore.given

  def load(): StoredState =
    if !Files.exists(path) then StoredState(Map.empty, Map.empty)
    else
      val raw = Files.readString(path)
      decode[StoredState](raw).fold(
        err => throw new RuntimeException(s"failed to parse state file $path: $err"),
        identity
      )

  def save(state: StoredState): Unit =
    val parent = path.getParent
    if parent != null then Files.createDirectories(parent)
    val tmp = path.resolveSibling(path.getFileName.toString + ".tmp")
    Files.writeString(tmp, state.asJson.spaces2)
    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

object StateStore:

  /** Default factory keeps the file-based store reachable as `StateStore(path)`
    * - preserves the call shape that tests and earlier Main code rely on. */
  def apply(path: Path): StateStore = new FileStateStore(path)

  given Codec[Role] = Codec.from(
    Decoder[String].emap(s => Role.parse(s)),
    Encoder[String].contramap(_.toString)
  )

  given Codec[Instant] = Codec.from(
    Decoder[String].emap(s => scala.util.Try(Instant.parse(s)).toEither.left.map(_.getMessage)),
    Encoder[String].contramap(_.toString)
  )

  given Codec[PoolKey] = Codec.from(
    Decoder[String].emap(PoolKey.parse),
    Encoder[String].contramap(_.toString)
  )

  given Codec[RoleDistribution] = deriveCodec
  given Codec[RunningNode]      = deriveCodec
  given Codec[StoredPool]       = deriveCodec
  given Codec[StoredTenant]     = deriveCodec
  // Hand-rolled so the `tenants` field is optional in the wire JSON - back-compat
  // with state files written before tenants became first-class.
  given Codec[StoredState] = Codec.from(
    Decoder.instance { c =>
      for
        pools   <- c.get[Map[String, StoredPool]]("pools")
        tenants <- c.getOrElse[Map[String, StoredTenant]]("tenants")(Map.empty)
      yield StoredState(pools, tenants)
    },
    Encoder.instance { s =>
      Json.obj(
        "pools"   -> s.pools.asJson,
        "tenants" -> s.tenants.asJson
      )
    }
  )