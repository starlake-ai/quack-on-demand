package ai.starlake.quack.example;

/**
 * Run a single SQL statement against the Quack-on-Demand FlightSQL edge and print the result.
 *
 * <pre>
 *   mvn -q compile exec:java
 *   mvn -q compile exec:java -Dexec.args="SELECT count(*) FROM tpch1.customer"
 * </pre>
 *
 * See {@link QodClient} for the JDBC plumbing. Connection settings come from {@code QOD_*} env vars
 * (see README).
 */
public final class Query {

  private static final String DEMO =
      "SELECT * FROM (VALUES (1, 'duck'), (2, 'quack'), (3, 'lake')) AS t(id, label)";

  public static void main(String[] args) {
    String sql = args.length > 0 ? args[0] : envOrDefault("QOD_SQL", DEMO);
    try (QodClient client = QodClient.connect(QodConfig.fromEnv())) {
      System.out.println("Connecting to " + client.describe());
      System.out.println("SQL: " + sql + "\n");

      QueryResult result = client.query(sql);

      System.out.println("columns: " + result.columnSummary());
      System.out.println("rows: " + result.rowCount() + "\n");
      int limit = Math.min(result.rowCount(), 100);
      for (int i = 0; i < limit; i++) {
        System.out.println(result.rowAsJson(i));
      }
    } catch (Exception err) {
      System.err.println("\nquery failed: " + err.getMessage());
      System.exit(1);
    }
  }

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value;
  }
}