//! Reusable FlightSQL client for Quack-on-Demand, over `arrow-flight` + `tonic`.
//!
//! Unlike Node (which has no first-party Flight SQL driver and must hand-roll the
//! gRPC + Arrow plumbing), Rust has `arrow-flight`'s `FlightSqlServiceClient`, so
//! this is a thin wrapper: build a `tonic` channel from `QOD_*` env vars, run a
//! statement, and collect the returned Arrow `RecordBatch`es.
//!
//! The edge authenticates every RPC from the call headers (`tenant`, `pool`,
//! `authorization: Basic <base64>`), which the client sends via `set_header`, so
//! there is no separate handshake step.

use std::fmt::Write as _;
use std::sync::Arc;

use arrow::array::RecordBatch;
use arrow::datatypes::SchemaRef;
use arrow::error::ArrowError;
use arrow::util::display::{ArrayFormatter, FormatOptions};
use arrow_flight::sql::client::FlightSqlServiceClient;
use base64::Engine as _;
use futures::TryStreamExt;
use tonic::transport::{Channel, ClientTlsConfig, Endpoint};

pub mod tpch_queries;

/// Connection settings for the Quack-on-Demand FlightSQL edge.
#[derive(Clone, Debug)]
pub struct QodConfig {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub password: String,
    pub tenant: String,
    pub pool: String,
    pub superuser: bool,
    pub tls: bool,
    pub tls_verify: bool,
}

fn env_or(name: &str, fallback: &str) -> String {
    std::env::var(name)
        .ok()
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| fallback.to_string())
}

fn env_bool(name: &str, fallback: bool) -> bool {
    match std::env::var(name).ok().filter(|v| !v.is_empty()) {
        Some(v) => v == "true",
        None => fallback,
    }
}

impl QodConfig {
    /// Build a config from `QOD_*` env vars, falling back to a local edge
    /// (127.0.0.1:31338) and the superuser `admin` against the `acme` tenant's
    /// `bi` pool.
    pub fn from_env() -> Self {
        QodConfig {
            host: env_or("QOD_HOST", "127.0.0.1"),
            port: env_or("QOD_PORT", "31338").parse().unwrap_or(31338),
            user: env_or("QOD_USER", "admin"),
            password: env_or("QOD_PASSWORD", "admin"),
            tenant: env_or("QOD_TENANT", "acme"),
            pool: env_or("QOD_POOL", "bi"),
            superuser: env_bool("QOD_SUPERUSER", true),
            tls: env_bool("QOD_TLS", true),
            tls_verify: env_bool("QOD_TLS_VERIFY", false),
        }
    }

    pub fn describe(&self) -> String {
        let proto = if self.tls { "grpc+tls" } else { "grpc" };
        let su = if self.superuser { " (superuser)" } else { "" };
        format!(
            "{proto}://{}:{} as {}@{}/{}{su}",
            self.host, self.port, self.user, self.tenant, self.pool
        )
    }
}

/// A connected FlightSQL client.
pub struct QodClient {
    inner: FlightSqlServiceClient<Channel>,
    cfg: QodConfig,
}

impl QodClient {
    pub async fn connect(cfg: QodConfig) -> Result<Self, Box<dyn std::error::Error>> {
        let channel = build_channel(&cfg).await?;
        let mut inner = FlightSqlServiceClient::new(channel);

        // The edge reads these headers on every RPC. authorization is HTTP Basic;
        // tenant/pool route the query; superuser=true selects the system realm and
        // bypasses the per-statement ACL gate.
        let basic = base64::engine::general_purpose::STANDARD
            .encode(format!("{}:{}", cfg.user, cfg.password));
        inner.set_header("authorization", format!("Basic {basic}"));
        inner.set_header("tenant", cfg.tenant.clone());
        inner.set_header("pool", cfg.pool.clone());
        if cfg.superuser {
            inner.set_header("superuser", "true");
        }

        Ok(QodClient { inner, cfg })
    }

    pub fn describe(&self) -> String {
        self.cfg.describe()
    }

    /// Run one SQL statement and return the decoded Arrow batches.
    pub async fn query(&mut self, sql: &str) -> Result<QueryResult, Box<dyn std::error::Error>> {
        let info = self.inner.execute(sql.to_string(), None).await?;
        let mut batches: Vec<RecordBatch> = Vec::new();
        for endpoint in info.endpoint {
            if let Some(ticket) = endpoint.ticket {
                let mut stream = self.inner.do_get(ticket).await?;
                while let Some(batch) = stream.try_next().await? {
                    batches.push(batch);
                }
            }
        }
        Ok(QueryResult { batches })
    }
}

/// A fully-materialized query result.
pub struct QueryResult {
    pub batches: Vec<RecordBatch>,
}

impl QueryResult {
    pub fn num_rows(&self) -> usize {
        self.batches.iter().map(|b| b.num_rows()).sum()
    }

    pub fn schema(&self) -> Option<SchemaRef> {
        self.batches.first().map(|b| b.schema())
    }

    /// `name: type` for every column, comma-joined.
    pub fn column_summary(&self) -> String {
        match self.schema() {
            None => "(no columns)".to_string(),
            Some(schema) => schema
                .fields()
                .iter()
                .map(|f| format!("{}: {}", f.name(), f.data_type()))
                .collect::<Vec<_>>()
                .join(", "),
        }
    }

    /// A one-line JSON-ish rendering of the first row.
    pub fn preview_row(&self) -> String {
        if self.num_rows() == 0 {
            return "(no rows)".to_string();
        }
        for batch in &self.batches {
            if batch.num_rows() > 0 {
                return row_as_json(batch, 0).unwrap_or_else(|e| format!("(format error: {e})"));
            }
        }
        "(no rows)".to_string()
    }

    /// JSON-ish rendering of a row at a global index across all batches.
    pub fn row_as_json(&self, mut index: usize) -> Result<String, ArrowError> {
        for batch in &self.batches {
            if index < batch.num_rows() {
                return row_as_json(batch, index);
            }
            index -= batch.num_rows();
        }
        Ok("{}".to_string())
    }
}

fn row_as_json(batch: &RecordBatch, row: usize) -> Result<String, ArrowError> {
    let opts = FormatOptions::default();
    let schema = batch.schema();
    let mut out = String::from("{");
    for (i, field) in schema.fields().iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        let col = batch.column(i);
        if col.is_null(row) {
            let _ = write!(out, "\"{}\":null", field.name());
        } else {
            let fmt = ArrayFormatter::try_new(col.as_ref(), &opts)?;
            let value = fmt.value(row).to_string();
            let escaped = value.replace('\\', "\\\\").replace('"', "\\\"");
            let _ = write!(out, "\"{}\":\"{}\"", field.name(), escaped);
        }
    }
    out.push('}');
    Ok(out)
}

// Build a tonic channel honoring the TLS settings:
//   * tls=false                -> plaintext h2c (grpc://)
//   * tls=true,  verify=true   -> TLS validated against the system trust store
//   * tls=true,  verify=false  -> TLS with certificate verification skipped, so
//     the edge's auto-generated self-signed cert is accepted (the default).
async fn build_channel(cfg: &QodConfig) -> Result<Channel, Box<dyn std::error::Error>> {
    // tonic's rustls backend needs a process-wide default crypto provider.
    let _ = tokio_rustls::rustls::crypto::ring::default_provider().install_default();

    if !cfg.tls {
        let endpoint = Endpoint::from_shared(format!("http://{}:{}", cfg.host, cfg.port))?;
        return Ok(endpoint.connect().await?);
    }

    if cfg.tls_verify {
        let tls = ClientTlsConfig::new()
            .with_native_roots()
            .domain_name(cfg.host.clone());
        let endpoint =
            Endpoint::from_shared(format!("https://{}:{}", cfg.host, cfg.port))?.tls_config(tls)?;
        return Ok(endpoint.connect().await?);
    }

    connect_skip_verify(cfg).await
}

// Skip-verification TLS: connect the h2 channel over a tokio-rustls stream whose
// certificate verifier accepts any server certificate. This mirrors the
// TypeScript example pinning the edge's self-signed cert off the wire, but
// without needing to fetch and parse it first.
async fn connect_skip_verify(cfg: &QodConfig) -> Result<Channel, Box<dyn std::error::Error>> {
    use hyper_util::rt::TokioIo;
    use tokio::net::TcpStream;
    use tokio_rustls::rustls::pki_types::ServerName;
    use tokio_rustls::rustls::ClientConfig;
    use tokio_rustls::TlsConnector;

    let provider = Arc::new(tokio_rustls::rustls::crypto::ring::default_provider());
    let mut config = ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(danger::NoVerify::new(provider)))
        .with_no_client_auth();
    // Advertise HTTP/2 via ALPN so gRPC is negotiated over the TLS session.
    config.alpn_protocols = vec![b"h2".to_vec()];
    let connector = TlsConnector::from(Arc::new(config));

    let host = cfg.host.clone();
    let addr = format!("{}:{}", cfg.host, cfg.port);
    // The verifier ignores the name, but rustls still requires a syntactically
    // valid ServerName; fall back to a placeholder for non-DNS/IP hosts.
    let server_name =
        ServerName::try_from(host.clone()).unwrap_or(ServerName::try_from("localhost").unwrap());

    // The custom connector performs the TLS handshake itself, so the endpoint
    // origin uses the http scheme; otherwise tonic reports "Connecting to HTTPS
    // without TLS enabled" because its own TLS layer is not configured here.
    let endpoint = Endpoint::from_shared(format!("http://{}:{}", cfg.host, cfg.port))?;
    let channel = endpoint
        .connect_with_connector(tower::service_fn(move |_uri: tonic::transport::Uri| {
            let connector = connector.clone();
            let server_name = server_name.clone();
            let addr = addr.clone();
            async move {
                let tcp = TcpStream::connect(addr).await?;
                let tls = connector.connect(server_name, tcp).await?;
                Ok::<_, std::io::Error>(TokioIo::new(tls))
            }
        }))
        .await?;
    Ok(channel)
}

mod danger {
    use std::sync::Arc;

    use tokio_rustls::rustls::client::danger::{
        HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier,
    };
    use tokio_rustls::rustls::crypto::{
        verify_tls12_signature, verify_tls13_signature, CryptoProvider,
    };
    use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName, UnixTime};
    use tokio_rustls::rustls::{DigitallySignedStruct, Error, SignatureScheme};

    /// A `ServerCertVerifier` that accepts any certificate. Used only when
    /// `QOD_TLS_VERIFY=false`, to accept the edge's self-signed cert. Signature
    /// checks still run against the crypto provider; only chain/name validation
    /// is skipped.
    #[derive(Debug)]
    pub struct NoVerify(Arc<CryptoProvider>);

    impl NoVerify {
        pub fn new(provider: Arc<CryptoProvider>) -> Self {
            NoVerify(provider)
        }
    }

    impl ServerCertVerifier for NoVerify {
        fn verify_server_cert(
            &self,
            _end_entity: &CertificateDer<'_>,
            _intermediates: &[CertificateDer<'_>],
            _server_name: &ServerName<'_>,
            _ocsp_response: &[u8],
            _now: UnixTime,
        ) -> Result<ServerCertVerified, Error> {
            Ok(ServerCertVerified::assertion())
        }

        fn verify_tls12_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, Error> {
            verify_tls12_signature(
                message,
                cert,
                dss,
                &self.0.signature_verification_algorithms,
            )
        }

        fn verify_tls13_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, Error> {
            verify_tls13_signature(
                message,
                cert,
                dss,
                &self.0.signature_verification_algorithms,
            )
        }

        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            self.0.signature_verification_algorithms.supported_schemes()
        }
    }
}
