package ai.starlake.quack.it.authz

/** Percentiles, the one-line report and the statements of the latency specs (Task 14). */
object Latency:

  /** The nearest-rank `p` percentile (0 < p <= 100) of `samples` (nanoseconds), in milliseconds. */
  def percentile(samples: Array[Long], p: Double): Double =
    val sorted = samples.sorted
    val rank   = math.max(1, math.ceil(p / 100.0 * sorted.length).toInt)
    sorted(rank - 1) / 1e6

  def line(arm: String, samples: Array[Long]): String =
    f"[authz-latency] $arm%-34s p50=${percentile(samples, 50)}%.3f ms " +
      f"p99=${percentile(samples, 99)}%.3f ms n=${samples.length}"

  /** What `arm` adds over `base` at p50 and p99 (each arm's own percentile, the difference). */
  def added(label: String, arm: Array[Long], base: Array[Long]): String =
    f"[authz-latency] added: $label%-27s " +
      f"p50=${percentile(arm, 50) - percentile(base, 50)}%.3f ms " +
      f"p99=${percentile(arm, 99) - percentile(base, 99)}%.3f ms"

  /** The most rounds a spec may run: every round of the node-level spec takes two fresh subsets
    * (its two miss arms), warm-up included, and the last subset is the fixed statement's.
    */
  val MaxRounds = 9000

  /** The rounds `asked` names (`QOD_AUTHZ_LATENCY_ROUNDS`), 2,000 when absent; anything but a
    * number from 1 to [[MaxRounds]] is refused.
    */
  def rounds(asked: Option[String]): Int =
    asked.fold(2000) { text =>
      text.trim.toIntOption match
        case Some(n) if n >= 1 && n <= MaxRounds => n
        case _                                   =>
          throw new IllegalArgumentException(
            s"QOD_AUTHZ_LATENCY_ROUNDS must be a number from 1 to $MaxRounds"
          )
    }

  /** The width of the wide table: every statement reads [[Picked]] of its columns. */
  val Columns = 18
  val Picked  = 6

  /** The wide table every arm reads: [[Columns]] integer columns of 1,000 rows, all equal. */
  def wideTable(name: String): String =
    val cols = (0 until Columns).map(c => s"i AS c$c").mkString(", ")
    s"CREATE TABLE $name AS SELECT $cols FROM range(1000) t(i);"

  /** Every [[Picked]]-column subset of the wide table, in one fixed order (18,564 of them). A plan
    * description names the columns a scan reads and no constant (a table function's arguments are
    * `[]` unless it reads files, CONTRACT Addendum 4 item 6), so the subset is what makes a plan
    * new: each subset is a fresh fingerprint, of the same cost as any other.
    */
  lazy val subsets: Vector[Seq[Int]] = (0 until Columns).combinations(Picked).toVector

  /** `SELECT count(c..), ... FROM table`, over subset `n`, with `head` projected first. */
  def counting(table: String, n: Int, head: String = ""): String =
    val counts = subsets(n).map(c => s"count(c$c)").mkString(", ")
    s"SELECT $head$counts FROM $table"
