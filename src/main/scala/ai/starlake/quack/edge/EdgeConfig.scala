package ai.starlake.quack.edge

final case class EdgeConfig(
    host: String,
    port: Int,
    tlsEnabled: Boolean,
    tlsCertChain: String,
    tlsPrivateKey: String,
    // After this many seconds the cached session for a Flight client
    // expires and the next RPC forces a fresh handshake - re-validating
    // the Basic credentials or the external Bearer token. Bounds the
    // "revoked credential still works" window without paying a JWKS
    // round-trip on every Flight call.
    sessionTtlSec: Long = 3600,
    // Largest gRPC message read, whatever the method and whoever sends it: gRPC refuses a larger one
    // with RESOURCE_EXHAUSTED as soon as its length prefix arrives. No bulk ingest exists and
    // DoPut batches are never read, so only SQL text needs room.
    maxInboundMessageBytes: Int = 16777216,
    // Largest header list read. gRPC buffers headers before any authentication runs.
    maxHeaderBytes: Int = 65536,
    // Calls with no credentials at once (GetFlightInfo and DoGet: AnonymousCallGuard).
    maxAnonymousCalls: Int = 256,
    // Threads for gRPC callbacks and DoPut tasks: a quarter of them may wait for DoPut data.
    flightThreads: Int = 512,
    // How long a DoPut may wait for its first message and its data. Not a setting: bound parameters
    // are never read, and a real client half-closes at once.
    putDrainTimeoutMs: Long = FlightProducerImpl.DefaultPutDrainTimeoutMs
)
