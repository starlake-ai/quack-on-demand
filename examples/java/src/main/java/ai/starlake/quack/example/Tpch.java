package ai.starlake.quack.example;

import ai.starlake.quack.example.TpchQueries.TpchQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Run the 22 TPC-H benchmark queries against the Quack-on-Demand FlightSQL edge and report the row
 * count, latency, and first-row preview for each.
 *
 * <pre>
 *   mvn -q compile exec:java -Dqod.mainClass=ai.starlake.quack.example.Tpch
 *   mvn -q compile exec:java -Dqod.mainClass=ai.starlake.quack.example.Tpch -Dexec.args="1 6 14"
 * </pre>
 *
 * The target schema defaults to {@code tpch1} (override with QOD_TPCH_SCHEMA); all other connection
 * settings come from the same QOD_* env vars {@link Query} uses (see README). The defaults connect
 * as the superuser admin to acme/bi.
 */
public final class Tpch {

  public static void main(String[] args) {
    String schema = envOrDefault("QOD_TPCH_SCHEMA", "tpch1");
    Set<Integer> selected = parseIds(args);
    List<TpchQuery> queries =
        selected.isEmpty()
            ? TpchQueries.ALL
            : TpchQueries.ALL.stream()
                .filter(q -> selected.contains(q.id()))
                .collect(Collectors.toList());

    int ok = 0;
    int failed = 0;
    double totalMs = 0;

    try (QodClient client = QodClient.connect(QodConfig.fromEnv())) {
      System.out.println("Connecting to " + client.describe());
      System.out.printf(
          "Running %d TPC-H query(ies) against schema '%s'%n%n", queries.size(), schema);

      for (TpchQuery q : queries) {
        String label = String.format("Q%02d %s", q.id(), q.title());
        String sql = TpchQueries.qualify(q.sql(), schema);
        long startedNs = System.nanoTime();
        try {
          QueryResult result = client.query(sql);
          double ms = (System.nanoTime() - startedNs) / 1e6;
          totalMs += ms;
          ok += 1;
          System.out.printf(
              "%-42s %6.0f ms  %6d rows  %s%n",
              label, ms, result.rowCount(), result.previewRow());
        } catch (Exception err) {
          failed += 1;
          System.out.printf("%-42s FAILED  %s%n", label, err.getMessage());
        }
      }

      double avg = totalMs / Math.max(ok, 1);
      System.out.printf(
          "%n%d ok, %d failed, %.0f ms total (%.0f ms avg)%n", ok, failed, totalMs, avg);
    } catch (Exception err) {
      System.err.println("\ntpch run failed: " + err.getMessage());
      System.exit(1);
    }

    if (failed > 0) {
      System.exit(1);
    }
  }

  private static Set<Integer> parseIds(String[] args) {
    List<Integer> ids = new ArrayList<>();
    for (String arg : args) {
      try {
        ids.add(Integer.parseInt(arg.trim()));
      } catch (NumberFormatException ignored) {
        // skip non-numeric args
      }
    }
    return new java.util.HashSet<>(ids);
  }

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value;
  }
}