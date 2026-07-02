package ai.starlake.quack.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Reusable FlightSQL client for Quack-on-Demand, over the Arrow Flight SQL JDBC driver.
 *
 * <p>Unlike Node (which has no first-party Flight SQL driver and must hand-roll the gRPC + Arrow
 * plumbing), Java has the Arrow Flight SQL JDBC driver, so this is a thin wrapper: build the JDBC
 * URL from {@code QOD_*} env vars, open a {@link Connection}, and run statements.
 *
 * <p>The edge authenticates every RPC from the call headers ({@code tenant}, {@code pool},
 * {@code authorization: Basic}), which the driver derives from the URL parameters, so there is no
 * separate handshake step.
 */
public final class QodClient implements AutoCloseable {

  private static final String DRIVER = "org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver";

  static {
    // The shaded Arrow allocator logs INFO chatter through java.util.logging (not
    // slf4j), so raise the threshold to keep the example's output clean.
    java.util.logging.Logger.getLogger("org.apache.arrow.driver.jdbc.shaded.org.apache.arrow")
        .setLevel(java.util.logging.Level.WARNING);
  }

  private final QodConfig cfg;
  private final Connection conn;

  private QodClient(QodConfig cfg, Connection conn) {
    this.cfg = cfg;
    this.conn = conn;
  }

  public static QodClient connect(QodConfig cfg) throws SQLException {
    try {
      Class.forName(DRIVER);
    } catch (ClassNotFoundException e) {
      throw new SQLException("Arrow Flight SQL JDBC driver not on the classpath", e);
    }
    Connection conn = DriverManager.getConnection(cfg.jdbcUrl());
    return new QodClient(cfg, conn);
  }

  public String describe() {
    return cfg.describe();
  }

  /** Run one SQL statement and return the fully-materialized result. */
  public QueryResult query(String sql) throws SQLException {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return QueryResult.from(rs);
    }
  }

  @Override
  public void close() throws SQLException {
    conn.close();
  }
}