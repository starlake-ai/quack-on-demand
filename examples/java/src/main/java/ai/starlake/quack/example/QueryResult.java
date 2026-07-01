package ai.starlake.quack.example;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A fully-materialized query result: column labels, their SQL type names, and the rows. Reading the
 * whole result into memory keeps the examples simple; for large results a streaming consumer would
 * be more appropriate.
 */
public record QueryResult(List<String> columns, List<String> types, List<Object[]> rows) {

  public static QueryResult from(ResultSet rs) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    List<String> columns = new ArrayList<>(n);
    List<String> types = new ArrayList<>(n);
    for (int i = 1; i <= n; i++) {
      columns.add(md.getColumnLabel(i));
      types.add(md.getColumnTypeName(i));
    }
    List<Object[]> rows = new ArrayList<>();
    while (rs.next()) {
      Object[] row = new Object[n];
      for (int i = 1; i <= n; i++) {
        row[i - 1] = rs.getObject(i);
      }
      rows.add(row);
    }
    return new QueryResult(columns, types, rows);
  }

  public int rowCount() {
    return rows.size();
  }

  /** {@code name: type} for every column, comma-joined. */
  public String columnSummary() {
    List<String> parts = new ArrayList<>(columns.size());
    for (int i = 0; i < columns.size(); i++) {
      parts.add(columns.get(i) + ": " + types.get(i));
    }
    return String.join(", ", parts);
  }

  /** A one-line JSON-ish rendering of a row (the first row for {@link #previewRow()}). */
  public String rowAsJson(int index) {
    Object[] row = rows.get(index);
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(columns.get(i)).append("\":").append(jsonValue(row[i]));
    }
    return sb.append('}').toString();
  }

  public String previewRow() {
    return rows.isEmpty() ? "(no rows)" : rowAsJson(0);
  }

  private static String jsonValue(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    return '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }
}