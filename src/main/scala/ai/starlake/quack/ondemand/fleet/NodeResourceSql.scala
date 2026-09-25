package ai.starlake.quack.ondemand.fleet

import io.fabric8.kubernetes.api.model.Quantity
import scala.util.Try

/** Engine-enforced cpu / memory for fleet nodes: the pool's Kubernetes quantities become DuckDB
  * SETs prepended to dbInitSql (a later operator SET still wins). Kernel limits are a v1 non-goal.
  */
object NodeResourceSql:
  private val MiB = BigDecimal(1024L * 1024L)

  private def threads(cpu: String): Option[Int] =
    Try(BigDecimal(Quantity.getAmountInBytes(new Quantity(cpu)))).toOption
      .map(v => v.setScale(0, BigDecimal.RoundingMode.CEILING).toInt.max(1))

  private def memoryMiB(memory: String): Option[Long] =
    Try(BigDecimal(Quantity.getAmountInBytes(new Quantity(memory)))).toOption
      .map(bytes => (bytes / MiB).setScale(0, BigDecimal.RoundingMode.FLOOR).toLong.max(64L))

  /** DuckDB SET statements for a pool's Kubernetes-style cpu / memory quantities, one per line,
    * empty string when neither is set. Floors: 1 thread, 64 MiB.
    */
  def render(cpu: Option[String], memory: Option[String]): String =
    val t = cpu.filter(_.nonEmpty).flatMap(threads).map(n => s"SET threads = $n;\n").getOrElse("")
    val m = memory
      .filter(_.nonEmpty)
      .flatMap(memoryMiB)
      .map(n => s"SET memory_limit = '${n}MiB';\n")
      .getOrElse("")
    t + m
