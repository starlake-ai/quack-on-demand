package ai.starlake.quack.example;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Connection settings for the Quack-on-Demand FlightSQL edge, built from {@code QOD_*} env vars.
 *
 * <p>The Arrow Flight SQL JDBC driver expresses the whole connection in its URL. Parameters the
 * driver does not recognize ({@code tenant}, {@code pool}, {@code superuser}) are forwarded to the
 * edge as gRPC call headers, which is exactly how the edge routes and authorizes the session.
 */
public record QodConfig(
    String host,
    int port,
    String user,
    String password,
    String tenant,
    String pool,
    boolean superuser,
    boolean tls,
    boolean tlsVerify) {

  /**
   * Build a config from {@code QOD_*} env vars, falling back to a local edge (127.0.0.1:31338) and
   * the superuser {@code admin} against the {@code acme} tenant's {@code bi} pool.
   */
  public static QodConfig fromEnv() {
    return new QodConfig(
        env("QOD_HOST", "127.0.0.1"),
        Integer.parseInt(env("QOD_PORT", "31338")),
        env("QOD_USER", "admin"),
        env("QOD_PASSWORD", "admin"),
        env("QOD_TENANT", "acme"),
        env("QOD_POOL", "bi"),
        envBool("QOD_SUPERUSER", true),
        envBool("QOD_TLS", true),
        envBool("QOD_TLS_VERIFY", false));
  }

  /** The {@code jdbc:arrow-flight-sql://} URL that carries the entire connection. */
  public String jdbcUrl() {
    StringBuilder url =
        new StringBuilder("jdbc:arrow-flight-sql://").append(host).append(':').append(port);
    url.append("?useEncryption=").append(tls);
    if (tls) {
      // Skip the cert check for the edge's auto-generated self-signed cert unless the caller
      // opted into a CA-signed cert with QOD_TLS_VERIFY.
      url.append("&disableCertificateVerification=").append(!tlsVerify);
    }
    url.append("&user=").append(enc(user));
    url.append("&password=").append(enc(password));
    url.append("&tenant=").append(enc(tenant));
    url.append("&pool=").append(enc(pool));
    if (superuser) {
      url.append("&superuser=true");
    }
    return url.toString();
  }

  public String describe() {
    String proto = tls ? "grpc+tls" : "grpc";
    String su = superuser ? " (superuser)" : "";
    return proto + "://" + host + ":" + port + " as " + user + "@" + tenant + "/" + pool + su;
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static boolean envBool(String name, boolean fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value.equals("true");
  }
}