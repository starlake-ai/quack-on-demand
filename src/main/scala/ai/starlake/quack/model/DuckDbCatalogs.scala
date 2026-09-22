package ai.starlake.quack.model

/** DuckDB's built-in catalog names. This constant guarantees only that the ACL attached-catalog
  * resolver (Main.scala) and the federated-alias validator agree on the BUILT-IN names - it is not
  * the full set either of them reasons about. The resolver's actual attached-catalog set is
  * `Builtins + <the tenant-db's own DuckDB catalog alias> ++ <every sibling federated alias>`
  * (Main.scala's `attachedCatalogsOf`); the validator's is `Builtins` plus whatever `extraReserved`
  * its caller passes to
  * [[ai.starlake.quack.ondemand.federation.iceberg.IcebergRestConfig.validated]]. Each caller is
  * responsible for composing its own fuller set on top of this shared base.
  */
object DuckDbCatalogs:
  val Builtins: Set[String] = Set("memory", "system", "temp")
