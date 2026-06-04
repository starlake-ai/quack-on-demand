package ai.starlake.quack.edge

final case class EdgeConfig(
    host: String,
    port: Int,
    tlsEnabled: Boolean,
    tlsCertChain: String,
    tlsPrivateKey: String,
    tenantClaim: String,
    // After this many seconds the cached session for a Flight client
    // expires and the next RPC forces a fresh handshake - re-validating
    // the Basic credentials or the external Bearer token. Bounds the
    // "revoked credential still works" window without paying a JWKS
    // round-trip on every Flight call.
    sessionTtlSec: Long = 3600
)