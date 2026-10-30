# Changelog

## 0.10.2

- **Security: upgrade promptly, and rotate the manager's secrets if tenant admins are not fully
  trusted.** A manager where tenant admins are not fully trusted, and where tenants could set their
  own sign-in configuration, should upgrade to this release promptly and then rotate every secret
  held in the manager's environment, in particular `QOD_SESSION_JWT_SECRET` (which also ends every
  open session), `QOD_PG_PASSWORD` and the cloud credentials. `QOD_API_KEY` is not rotated but
  unset: this release removes the static API key, and a manager with it set refuses to boot (see
  below). Also review the personal access tokens owned by superuser rows and the recent superuser
  sign-ins in the audit log.

- **Security: BREAKING: a tenant's sign-in stays inside that tenant.** Management sign-in and the
  tenant sign-in settings now hold these rules:
  - A tenant SSO login takes its grants from that tenant's `qodstate_user` rows only, the email
    fallback included: an identity whose rows are all in other tenants, or whose only row is a
    superuser's, is refused `403 not_provisioned` (the browser lands on
    `/ui/?error=not_provisioned`), and superusers sign in through the bare `/ui/` login or a
    password login without a tenant. Before, the lookup matched the identity against every
    realm's rows.
  - A tenant that signs in with passwords takes no SSO: `/api/auth/oidc/start` answers `400
    oidc_not_configured` and the callback lands on `/ui/?error=oidc_not_configured` for a tenant
    whose provider is `db`, whatever sign-in keys its auth config still holds: the provider
    decides, not the leftover keys. An OIDC tenant whose sign-in is incomplete (no issuer, client
    id or client secret), and an unknown tenant, take no SSO either.
  - BREAKING: the same rule now covers a password login answered by the manager-wide Keycloak or
    Azure password grant (`QOD_AUTH_KEYCLOAK_ENABLED`, `QOD_AUTH_AZURE_ENABLED`). The basic chain
    tries it after the database authenticator, so with one enabled a tenant's password form also
    accepts that IdP's passwords. Such a login took its rights from the IdP's role claim, so an
    `admin` claim opened a superuser session through any `db` tenant's login form, or without a
    tenant under `identitySource=db`, with no `qodstate_user` row at all. It now signs in as the
    QoD user with the same user name as the IdP account, matched by the token's `preferred_username`
    (see the next bullet for a token without one) and never retried with the email claim, and gets
    that row's rights only: for a tenant login a row of that tenant (an admin row opens the
    console, a `user` row the Profile page), for a login without a tenant the rows the user name
    holds in any realm (a superuser row, or admin rows of one or more tenants). No row answers
    `403 not_provisioned`, and an IdP role claim no longer makes anyone admin or superuser. Since
    the user name picks the row, the IdP's user names must be assigned by an administrator, not
    chosen by the account holder: an IdP realm that lets people choose their own user name lets
    them sign in as any QoD user of that name, the seeded superusers `admin@localhost.local` and
    `admin` included. This holds on the data plane too: a FlightSQL or native Quack front door
    handshake binds the row its token's `preferred_username` names, a superuser row on a
    `superuser=true` handshake (a Keycloak realm with "email as username" and self-registration
    lets a registrant choose `admin@localhost.local`). Operators who relied on IdP `admin` claims create the rows first (`qod user
    create`). Logins verified by the database authenticator are unchanged.
  - BREAKING: an identity-provider login counts an email address only when the IdP marks it
    verified (`email_verified` true), because an address the account holder typed could otherwise
    name another user's row, the superuser's included. The IdP authenticators (the OIDC and
    external JWT bearers, the password grant) name the user by the token's `preferred_username`,
    else its email claim, else its subject. A user name that came from the email claim (a token
    without `preferred_username`) binds a row only when that address is verified, else the login
    is refused `403 not_provisioned`; single sign-on also falls back to the email claim, when the
    user name matches no row, only when it is verified. A provider that does not emit
    `email_verified` no longer matches rows by email: have its tokens carry `preferred_username`
    and name the rows by it, or have them carry `email_verified`. The rule holds on every plane: a FlightSQL or native Quack front door
    handshake whose user name came from an unverified email claim (the manager-wide password
    grant answering a basic handshake, a manager-wide bearer, a tenant's own bearer) is refused
    as unauthenticated, with a message naming the cause, and binds no row. Before, such a name
    bound any row it matched, the seeded superuser `admin@localhost.local` included on a
    superuser handshake. A token that names no user (no `preferred_username`, email or sub) is
    refused everywhere too: a password-grant token is never named by the text typed into the
    form, and a bearer token is no longer named `unknown`; on the data plane a blank user name
    is refused rather than replaced by the token's subject.
  - BREAKING: a token that carries an `email` claim but no `preferred_username`, and whose
    `email_verified` is not true, is now refused on the management and the data plane alike (a
    token with neither claim is named by its subject, as before, and is unaffected). Remedy: have
    the tokens carry `preferred_username`, or `email_verified: true`. The two setups most likely
    to hit it:
    - an operator-run external JWT signer (`JWT_SECRET_KEY` / `JWT_PUBLIC_KEY_PATH`, data plane
      only, so its naming changes for the first time in this release) whose tokens name users by
      `email`: add `preferred_username`, or `email_verified: true`, to the tokens it mints;
    - Azure AD (Entra ID), which never sends `email_verified`: a v1 access token carries no
      `preferred_username`, so one named by its `email` claim is always refused. Issue v2 tokens
      (`accessTokenAcceptedVersion: 2` in the app registration's manifest), which carry
      `preferred_username`.
  - A REST or MCP statement binds a superuser row only when its caller is a superuser (a
    superuser session, or a PAT owned by a superuser); any other caller binds a row of its own
    tenant, as the FlightSQL handshake already ruled.
  - A tenant's OIDC client secret is a write-only stored value that its own admins may set:
    `tenant/setAuth` field `clientSecret`, MCP `set_tenant_auth` argument `client_secret`, `qod
    tenant set-auth --client-secret-prompt` or `--client-secret-stdin` (`--clear-client-secret`
    removes it), and the admin UI's Auth Provider page. No response carries it
    (`clientSecretSet` says whether one is stored), a manifest exports it as `***REDACTED***`,
    and it is bound to the issuer it was saved with: a save that changes `issuerUrl` without a
    new secret removes it, as the tenant OPA token never follows an OPA URL change. The admin UI
    warns before a save that would remove it, or that would leave the tenant's OIDC sign-in
    incomplete (an issuer URL, a client ID and a secret are all needed).
  - A `clientSecretRef` (`env:NAME`) reads the manager's own environment, so only a superuser may
    add or change one: anyone else gets `403 superuser_required` and nothing is written. Under a
    new issuer a carried reference survives only in a superuser's write, which may pair a
    reference with the new issuer; a tenant admin's carried reference is dropped. A tenant has one
    secret source (`400 secret_conflict` for a value and a new reference in one write); a value
    made only of whitespace is refused (`400 invalid_client_secret`), and so is a `clientSecret`
    key inside `authConfig` (`400 invalid_auth_config`).
  - BREAKING: a password login naming a tenant whose console sign-in is OIDC (`POST
    /api/auth/login` with that `tenant`, `qod login --tenant`) answers `400 sso_required`: open
    `/ui/?tenant=<tenant id>`, or use a personal access token. No password is checked and no
    lockout counter moves. SQL clients keep their passwords: FlightSQL and the native front door
    are unchanged. Tenant SSO now works under either `identitySource`: the admin-console SSO
    service is wired whatever `QOD_MGMT_IDENTITY_SOURCE` says, which now picks only the bare
    `/ui/` login mode (under `db` it stays a password form, even with `QOD_MGMT_OIDC_*` set).
    Before, `/api/auth/oidc/start` answered `404 auth_mode_disabled` unless it was `oidc`, so
    under the default `db` such a tenant's admins signed in with passwords; they now sign in
    through the tenant's identity provider. Upgrade step: a manager running `identitySource=db`
    that serves tenant SSO must set `QOD_MGMT_PUBLIC_BASE_URL` and register
    `${QOD_MGMT_PUBLIC_BASE_URL}/api/auth/oidc/callback` as a redirect URI on each tenant's IdP
    client. Left unset, the redirect URI the manager sends is
    `http://localhost:<manager port>/api/auth/oidc/callback`, and the boot warning about it only
    appears under `identitySource=oidc`, at WARN, below the default ERROR level.
  - BREAKING: secret references saved before this release are parked, since who saved them was
    not recorded. Liquibase `0048` adds the `qodstate_tenant.auth_client_secret` column and moves
    each tenant's `clientSecretRef` to `clientSecretRefUnconfirmed`, which nothing reads. After
    the upgrade every tenant whose sign-in used a reference is misconfigured until a superuser
    restores it: its console sign-in answers `tenant_oidc_misconfigured` and its password login
    is refused too, so only a superuser, through the bare `/ui/` login, can open its console. A
    superuser restores it by saving the reference again (`qod tenant set-auth <tenant>
    --auth-provider <provider> --auth-config issuerUrl=... --auth-config clientId=...
    --auth-config clientSecretRef=<reference>`; the save replaces the whole auth config); the
    tenant's admins can store the secret themselves instead, but only with a personal access
    token or a session they already hold. Every boot logs an error line per parked tenant naming
    the reference and that command (a parked value that is not a secret reference is not
    printed, and its line asks for a secret source instead), and asking the superuser to confirm
    that the tenant's `issuerUrl` is its own identity provider. A save that carries a secret
    value or a reference drops the parked key, through any client (REST, MCP, the CLI or the
    admin UI), which ends the error line; so does a save that changes `issuerUrl` or leaves the
    key out, but the sign-in then stays unconfigured until a secret source is set. A manifest
    exported before this release still carries active `clientSecretRef` values, and importing it
    re-activates them without this review: check those references first. In an HA rolling
    upgrade, finish the rollout before tenant admins edit sign-in settings: a 0.10.1 replica
    still serving writes accepts a reference from a tenant admin without the superuser rule, and
    what it writes after the migration ran is not parked, so otherwise review the tenants'
    references afterwards (`qod --json tenant list`).
  - Manifests: a tenant's `clientSecret` is exported as `***REDACTED***` and imports as the
    tenant OPA token does: the marker keeps the stored value only while `issuerUrl` is unchanged
    and is refused otherwise, a whitespace-only value is refused (an empty string means no
    secret), and so is a tenant that carries a secret and a `clientSecretRef`, or a
    `clientSecret` key inside `authConfig`. BREAKING: a tenant with no `clientSecret` in the
    manifest ends up with none, so a hand-written manifest that leaves it out removes a stored
    secret.
  - BREAKING: the per-tenant FlightSQL bearer override no longer reads a `clientSecretRef`, which
    bearer validation never used: a Google, Keycloak or Azure AD tenant that set its other
    per-tenant fields without one now validates bearer tokens against its own client instead of
    the manager-wide one, so its tokens must carry that client id as audience (existing tenants
    in that state change behavior as soon as this release runs). Its own admins can turn this on,
    since the override needs no superuser-only reference.

- **BREAKING: the static API key is gone.** The manager no longer accepts `QOD_API_KEY` /
  `quack-on-demand.apiKey`: every REST, MCP and SCIM caller is a session or a personal access
  token (PAT), and every admin action is attributed to a user. A non-empty `QOD_API_KEY` in the
  environment, or a non-empty `quack-on-demand.apiKey` in an operator's config file, stops the
  boot with "QOD_API_KEY (quack-on-demand.apiKey) was removed: the manager no longer accepts a
  static API key. Use a personal access token (qod auth pat create) or a qod login session, and
  unset QOD_API_KEY."; an empty value passes (older Compose files render `API_KEY=` as one). Boot
  no longer generates a key, prints no secret (see the session secret entry below), and it
  refuses to start when nothing could create a session: no password backend
  (`QOD_AUTH_DB_ENABLED=false` and no Keycloak or Azure one) and no system-scope OIDC sign-in
  (`QOD_MGMT_IDENTITY_SOURCE=oidc` with `QOD_MGMT_OIDC_ISSUER_URL` and `QOD_MGMT_OIDC_CLIENT_ID`
  set). These refusals read the configuration alone and come first, before the manager binds a
  listener, migrates its database or seeds an admin (on the embedded control plane its Postgres
  is already up, and is stopped again). The
  admin UI's no-login mode is gone too: with no auth provider the UI used to skip the login
  screen and run as a synthetic `anonymous` superuser; such a manager now refuses to boot, so
  the UI always shows a login. What replaces each use of the key:
  - an operator's CLI or curl: a `qod login` session, or a PAT in `QOD_TOKEN`, sent as
    `X-API-Key` (the header keeps its name); a 401 from the CLI says "run qod login, or set
    QOD_TOKEN to a personal access token". `GET /api/auth/whoami` (`qod whoami`) now accepts a
    live PAT too and answers with its owner's identity and the token's own scope, where it used
    to answer `401 invalid`;
  - CI and IaC: a PAT an admin mints once (`qod auth pat create`), or `qod login
    --password-stdin` inside the job (the password on the first line of stdin, never in a flag or
    an env var);
  - MCP clients: a PAT as `Authorization: Bearer`, the only credential `/mcp` admits (a superuser
    PAT still passes `tenant` explicitly);
  - SCIM connectors: a tenant-admin PAT as `Authorization: Bearer`, pinned to its tenant;
  - Starflow's pool listing: the user's own session against `GET /api/profile/pools` (below);
  - recovery: `qod admin reset-password`, which now re-enables a disabled superuser when
    `--username` names it (its personal access tokens then work again; a reset of the configured
    admin names without `--username` leaves a disabled account disabled and says so); a deleted
    superuser row comes back at the next boot with `QOD_ADMIN_PASSWORD` set.

- **Sessions survive a restart without `QOD_SESSION_JWT_SECRET`, and HA no longer needs it.** A
  manager with no session secret configured used to mint a random one per boot and print it in a
  `GENERATED BOOT SECRET` banner, so every UI and CLI session (and every password-reset link) died
  with the process, and HA refused to boot. The first boot now generates the secret once and
  stores it in the control-plane database (new table `qodstate_boot_secret`, Liquibase `0049`, row
  `session_jwt`); every later boot and every HA replica reads the same row (replicas booting
  together converge on one value). The banner is gone: boot prints one line naming the source,
  `session secret: from QOD_SESSION_JWT_SECRET (quack-on-demand.auth.management.sessionJwtSecret)`,
  `session secret: generated and stored in the control plane` or `session secret: stored in the
  control plane`, never the value. A non-empty
  `QOD_SESSION_JWT_SECRET` still wins and is never written to the database (the stored row is left
  as it is, so unsetting the variable falls back to it); `qod start --with-starflow` and Compose's
  Starflow profile keep pinning one, since Starflow verifies the manager's tokens with it. HA no
  longer refuses an empty secret, and the Helm chart's `sessionJwtSecret` /
  `existingSessionJwtSecret` are optional at any `replicaCount` (when set, the chart now passes
  them with one replica too, where it used to ignore them). Rotate the stored secret with
  `DELETE FROM qodstate_boot_secret WHERE name = 'session_jwt'` and a restart of every replica:
  every session and outstanding reset link ends. Trust boundary: a writer of the control-plane
  database (its admin, or anyone holding the control-plane login, nodes that inherit it included)
  gains nothing new, since it can already rewrite `qodstate_user`. A read-only holder (a `pg_dump`
  backup, a snapshot, a read replica, a SELECT-only role) now also gets the management plane: on an
  install with no `QOD_SESSION_JWT_SECRET` it can forge a superuser session. That comes on top of
  the credentials the same database already stores, among them tenant catalog `pgPassword` and
  duckdb-file encryption keys, object-store keys, inline federation secrets, the node-key secret,
  the manager's bearer to each node, tenant OIDC client secrets and tenant OPA tokens. Protect control-plane backups and replicas like the secret itself;
  when they are less trusted than the manager, pin `QOD_SESSION_JWT_SECRET` from an external secret
  store: nothing is persisted then, and backups never carry it.

- **REST and MCP data statements run as their caller.** Preview, data diff, undrop, restore,
  Iceberg views, branch counts requested over REST and MCP `run_sql` go through the FlightSQL
  handshake's gate (tenant and pool kill switches, user registered and enabled, pool access) and
  run in-engine as that user's principal, never as `System`, which stays for the manager's own
  work. A superuser known only through OIDC, with no `qodstate_user` row, is refused there, as on
  FlightSQL. The self-lock and self-delete guards now apply to every caller, the audit and the
  telemetry no longer record a `static-key` actor, and `qod fleet approve` records the approving
  user in `approved_by`.

- **BREAKING for the CLI and its launchers.** `api_key` and its `QOD_API_KEY` env name leave the
  CLI settings; `token` (`QOD_TOKEN`, a session or a PAT) is its only credential, and `qod status`
  lists pools with it. `qod setup` loses its key prompt and `--api-key`. `qod start` and
  `qod serve` (with or without `--demo`) refuse to launch while `QOD_API_KEY` is set in the shell
  or stored in the profile's `[start]` table, naming where it is and how to remove it
  (`unset QOD_API_KEY`, or delete the line from the `[start]` table of the named profile file).
  `qod serve` no longer hands its manager a per-boot key: it writes a spec file and the manager
  provisions the tenant, database and pool itself (`QOD_SERVE_PROVISION`, an add-only boot hook
  that never updates or deletes, reporting to `serve-provision.outcome.json`), so later boots
  need no credential to provision. A later boot waits for a routable node with `QOD_TOKEN` or
  the profile's unexpired session when that credential can list pools, and prints the
  node-warming note only when none can. The spec file is deleted once the manager reported, and
  in any case when the manager exits (Ctrl-C, a refused boot, a release that never reads it).
  An existing database whose kind, data path or init SQL differ, or a pool name already serving
  another database, is refused with the way out (`qod serve <target> --name <other>` or
  `--pool <other>`), on its own line in attach mode, never as a `finish by hand:` command; the
  manager's reason names the existing row's kind and data path, not the ones the serve asked
  for. Any other refused step is printed with its `finish by hand:` command. A launcher/manager
  version mismatch now says to run the manager release matching this CLI (`qod --version`
  shows it). `qod setup --no-auth` (or `QOD_AUTH_DB_ENABLED=false`) warns that the manager
  then refuses to boot without another authenticator. Attach mode (a manager already running) provisions with
  `QOD_TOKEN`, the profile's session or a password prompt, and otherwise refuses with "run qod
  login, set QOD_TOKEN, or run qod serve in a terminal". `--with-starflow` now shares only the
  session secret with Starflow.

- **BREAKING for Helm, Compose and Docker.** The chart drops `apiKey.*`, its `<fullname>-apikey`
  Secret and the `QOD_API_KEY` env it rendered; it fails on an `apiKey` value with "apiKey was
  removed: the manager no longer accepts a static API key; use a personal access token" (an
  all-empty block left from an older values file passes) and requires `admin.password` or
  `admin.existingSecret`, since nobody could log in without an admin. `docker-compose.yml`
  passes no `API_KEY` to the manager or to Starflow, `starflow-db-init` needs only
  `SESSION_JWT_SECRET`, and `scripts/docker/run-docker-compose.sh` and `run-docker.sh` stop
  generating and forwarding it (each warns about a leftover `API_KEY`). `run-docker.sh` now
  refuses `AUTH=false` up front, since it can pass no other FlightSQL authenticator; under
  Compose, `AUTH=false` boots only with another FlightSQL authenticator and a way to sign in to
  the admin UI (see the next entry and `.env.example`). The kind rig and `test-api.sh` log in as
  the seeded admin.

- **BREAKING: the trust-the-client FlightSQL and Quack mode is gone.** With no
  `quack-flightsql.auth` provider at all (`QOD_AUTH_DB_ENABLED=false` and no Keycloak, Azure,
  Google, AWS or external JWT provider), the FlightSQL edge and the Quack front door trusted the
  client: they bound whatever user it named, superusers included, with no password, and the edge
  logged `auth: OPEN (trust-the-client ...)`. The manager now refuses to boot in that state, with
  "the FlightSQL edge and the Quack front door have no authenticator: ...", naming what to set:
  `QOD_AUTH_DB_ENABLED=true`, a Keycloak or Azure password backend (`QOD_AUTH_KEYCLOAK_ENABLED`,
  `QOD_AUTH_AZURE_ENABLED`), or a bearer provider (`JWT_SECRET_KEY` or `JWT_PUBLIC_KEY_PATH`,
  `QOD_AUTH_GOOGLE_ENABLED`, `QOD_AUTH_AWS_ENABLED`). System-scope OIDC (`QOD_MGMT_OIDC_*`)
  signs in to the admin UI and REST only and adds no FlightSQL provider, so `AUTH=false` with
  system-scope OIDC alone is refused too. The handshake itself fails closed as well: with no
  provider configured, the FlightSQL edge and the Quack front door refuse every connection,
  superusers included ("no authenticator is configured on this manager: every connection is
  refused"), where they used to trust the client, and the edge no longer logs `auth: OPEN`. A
  password login on such a service answers `401 invalid_credentials`, not `503 auth_disabled`.

- **`GET /api/profile/pools`: the pools you may connect to.** Any session or PAT (the endpoint
  joins the profile allowlist) gets
  `{"pools":[{"tenant":...,"tenantDb":...,"pool":...,"status":...}]}`, the field names and
  `status` values (`ready`, `disabled`) of `/api/pool/list` and nothing else: the pools of the
  caller's tenants (every tenant for a superuser) that the FlightSQL handshake's own decision
  admits (kill switches, user enabled, then the pool grant or the tenant's OPA; a superuser skips
  the pool step), narrowed by a PAT's `databases` and `pools`. A denied pool is omitted; an
  unavailable decision (the tenant's OPA unreachable) answers `503 authz_unavailable`. CLI:
  `qod profile pools`.

- **Starflow lists pools as the user.** A separate starlake-api change lists pools through
  `GET /api/profile/pools` with the user's own QoD session and drops its `QOD_API_KEY`,
  `QOD_SERVICE_USERNAME` and `QOD_SERVICE_PASSWORD` settings, so QoD, not Starflow, decides which
  pools a user sees. Until it ships, an older Starflow still lists pools for admins (its fallback
  to the user's session reaches `/api/pool/list`); non-admins get 403, as they do today without
  a key.

- **Security: OPA transport details now go to the manager log only.** When a tenant's OPA cannot
  decide (unreachable, timed out, a non-2xx status, a body over 1 MiB, no URL), the FlightSQL and
  Quack handshakes, every statement, the REST and MCP data tools and `GET /api/profile/pools`
  answered with the transport error, which could name a host, port or status. The tenant admin
  chooses the OPA URL, so that let a tenant probe whatever the manager's network reaches. They now
  answer "OPA unreachable or returned an error (see the manager log)" (still `UNAVAILABLE` /
  `503 authz_unavailable`), the same text `qod tenant opa-test` now shows a non-superuser, and the
  statement history (`/api/profile/statements`) records that text too. The cause is only in the
  manager's WARN line `OPA <connect|statement> error for tenant=<id>: ...`, where the bearer token
  is now masked.

- **BREAKING: nodes run qod-node instead of the DuckDB CLI.** Every node, on every backend, is now
  `qod-node` (native/qod-node): QoD's own executable on the unmodified official DuckDB release
  library, the host the node-side authorization below builds on. The spawn scripts hand it the init
  SQL on its stdin, never on disk; it runs it statement by statement and carries on past a failing
  one, as the CLI did, with no CLI dot-commands, and a failing statement is logged without DuckDB's
  `LINE n:` excerpt (init SQL can carry credentials). It reads the node token and the node key
  itself from the node's secret files and exits with its wrapper or on SIGTERM. Which binary:
  - a manager that starts nodes on its host (runtime local, or fleet with
    `QOD_FLEET_EPHEMERAL=local`) uses `QOD_NODE_BIN` when set, else the binary the jar bundles for
    Linux and macOS (x86_64, aarch64), installed beside the libduckdb it loaded; it refuses to boot
    without one, or on one whose `--build-info` names another node protocol or DuckDB release;
  - on Windows, `qod start`, `qod serve` and `run-jar.ps1` download the release's
    `qod-node-windows-<arch>.exe` (too large to bundle) and set `QOD_NODE_BIN`;
  - `qod fleet join` runs the copy the qod wheel bundles (or `--qod-node-bin`);
  - the Kubernetes node image ships the vendored binary and libduckdb in `/opt/qod-node`, and no
    DuckDB CLI.
  Operator action: restart every node after upgrading, and on Kubernetes pull this release's node
  image.

- **Security: BREAKING: every statement on a node runs for a principal the manager vouches for.**
  Before each statement it sends a node (a relayed client statement, a stamp prelude, a COMMIT,
  the health probe), the manager sends its own `CALL system.main.qod_session('<handle>')`: a
  session handle naming the principal (a user, a superuser, or the manager's system principal for
  probes, statistics, maintenance and merges), the node, the pool, the policy version, a
  single-use nonce and a 60 s expiry, signed with that node's own key. qod-node installs it on the
  connection, keeps one principal per connection, and refuses any statement, at its parse screen
  and again after binding, on a connection with no current principal. A refused handle drops the
  connection before the statement is sent (retryable). The health probe also sends a handle-less
  `SELECT 1`: a node that runs it does not enforce, and is marked unhealthy and never routed to.
  Each node's key is HMAC-SHA256 of the node-key secret and the node id, delivered as the secret
  file `QOD_NODE_AUTHZ_KEY` (with the id as `QOD_NODE_ID`, on Kubernetes in the pod's token Secret,
  in fleet mode in the assignment), never in argv or env. The node-key secret is
  `QOD_NODE_KEY_SECRET` (`quack-on-demand.nodeAuthz.keySecret`, at least 32 characters, else boot
  refuses) when set, else one value the first boot generates and keeps in the control plane
  (`qodstate_node_authz`, Liquibase `0047`), shared by every replica. Consequences:
  - Under HA, set `QOD_NODE_KEY_SECRET` the same on every replica, or leave it unset on all: a node
    whose key derives from another secret refuses the handles, and the health loop names that
    cause.
  - Changing it, or deleting the stored row, means restarting every node.
  - Trust boundary: unless `QOD_NODE_KEY_SECRET` is set, whoever can read the control-plane database
    (backups, dumps, read replicas, a SELECT-only role) can mint a handle for any principal on any
    node, on top of the credentials that database already stores. Protect it like the secret
    itself, or pin `QOD_NODE_KEY_SECRET` from an external secret store.
  - Every handle also names the replica that minted it (`QOD_INTERNAL_URL`, see "nodes call the
    manager back" below).
  - Operator action: restart every node after upgrading. A node an earlier release started does
    not know `qod_session` and is never routed to.

- **Security: BREAKING: the nodes decide every tenant statement on what DuckDB resolved.** Each
  node (qod-node, node protocol 4) now judges a statement of a user who is not a superuser on the
  plan DuckDB bound for it, before it runs: the tables, views and columns it reads or writes, the
  functions and settings it calls, the files it names. It asks the manager replica that minted the
  session for a verdict (`POST /api/internal/authz/decide`, signed with the node's key, never in
  the OpenAPI), and the replica answers from the same grants (or the tenant's OPA policy), the same
  function and setting classification and the same file rules the gateway uses. Verdicts are cached
  on the node for 60 s (an OPA tenant's for its OPA cache TTL) and dropped as soon as a grant,
  membership, ACL setting or data folder changes (`qodstate_tenant.policy_version`, Liquibase
  `0050` and `0054`); an OPA tenant's policy change, which bumps nothing, takes effect as those
  verdicts expire. The gateway's own checks still run first. Consequences:
  - `QOD_ACL_ENABLED=false` no longer opens tables to tenant users: it turns off the gateway's
    check only, and the node refuses any statement no grant covers. A manager that relied on the
    ACL being off to let tenant users read everything must grant them first (the built-in
    `qod_all_tables` role, `*.*.* ALL`, is what an omitted `--role` gives a new user).
  - Plain views run with the reader's rights (the reader needs a grant on what the view reads);
    secure views, which only superusers create, run with their owner's.
  - Node refusals read `QoD: <reason>`. A statement refused because no verdict could be had in time
    (the replica restarted, is down or unreachable from the node) reads `QoD: authorization
    unavailable, retry` and is retryable: FlightSQL answers it `UNAVAILABLE` (every other node
    refusal stays a permanent error), and the native front door relays the node's message. Clients
    may retry it as they retry a transient failure.
  - A fresh FlightSQL query costs two verdicts (its schema probe and the statement), about 1.4 ms
    end to end on one host (0.7 to 0.9 ms per verdict); a cached one adds about 0.3 ms per
    statement on the node. Bearer-token
    sessions share no verdict across tokens.
  - Superusers and the manager's own work are not decided: their statements are still described,
    and the node's local refusals apply (statement shapes the node cannot describe, and `SET` /
    `RESET` of `disabled_optimizers`, `enable_optimizer`, `debug_disable_optimizer`,
    `quack_authentication_function` and `quack_authorization_function`, refused to every principal:
    `QoD: <name> cannot be changed`).
  - Operator action: restart every node after upgrading. A node started by an earlier release, or
    running a qod-node of an earlier node protocol (a Kubernetes pod kept across the upgrade, a
    fleet server on an earlier wheel, an image built from stale binaries), is never routed to on
    any backend: session handles are now version 2, and such a node refuses every one, the health
    probe's included, so it stays unhealthy. Release note: the vendored qod-node binaries
    (`native/qod-node/binaries/`, the qod wheel's bundled copies for `qod fleet join`, the Windows
    downloads and the Kubernetes node image) move to node protocol 4 with this release; a manager
    that starts nodes on its host refuses to boot on a binary of another protocol, `qod fleet join`
    refuses to run one (exit 2), the node image build fails on one, and protocol 4 nodes refuse an
    init stream without this release's nonce line.

- **BREAKING: statements tenant users can no longer run.** To keep the node's decision sound, a
  user who is not a superuser is refused:
  - any function the classification does not list, anywhere in the statement or in a view,
    macro or stored expression it reaches: `QoD: function <name> is not classified` (a misspelled
    function reads that way too); a macro a superuser created, and a macro in any catalog named like
    a built-in (which also makes the built-in macros calling that name unreadable), until it is
    called as `system.main.<name>` or the macro is dropped or renamed;
  - a plain view whose body calls a user macro, an unclassified or superuser-only function,
    the `histogram(<table>, <column>)` table macro, or a `query()` with a computed argument or one
    that runs a statement other than a query, or that nests views more than 16 deep;
    a table whose generated column, column default, CHECK constraint or trigger does the same (it
    becomes unreadable or unwritable for users);
  - a secure view whose definition names a table, function, macro, type or sequence without
    qualifying it, reads a plain view or a user-defined type, advances a sequence, calls
    `current_setting()`, or uses `query()` or a time-travel read; and any secure view read while the
    session holds a temporary view, macro or type (DuckDB's own dynamic-PIVOT types and temporary
    indexes excepted), a `SET VARIABLE` variable, a session setting it changed other than `schema` /
    `search_path` (a client that sets `TimeZone` on connect included), or a search path other than
    the session's default (a `USE` of another schema): `QoD: statement uses a secure view while the
    session ..., which QoD does not support`, for as long as the session holds that state. Superusers write secure views fully qualified, over base tables and other secure views
    only;
  - `CREATE MACRO`, `CREATE` / `DROP SECRET` in any storage, `ATTACH`, `IMPORT` / `EXPORT
    DATABASE`, `CONNECT`, `DESCRIBE (<query>)` / `SUMMARIZE (<query>)`, a PRAGMA off a read-only
    list, `current_setting()` of any setting, the catalog-wide listings nothing filters yet
    (`duckdb_constraints()`, `duckdb_indexes()`, `duckdb_sequences()`, `duckdb_triggers()`,
    `duckdb_types()`, `duckdb_functions()`, `duckdb_dependencies()`), and `SET` / `RESET` of the
    `s3_*`, `external_file_cache_*`, secret, certificate, proxy, HTTP client and cache settings;
  - a file named in a table position (`FROM 'x.parquet'` is now "table not found": use the reader
    function);
  - with the ACL on and `QOD_ACL_FILTERED_METADATA` off, `SHOW TABLES` (refused at the gateway; it
    used to list every table of the session schema unfiltered):
    turn the filter on, or query `information_schema.tables`;
  - `ALTER TABLE ... ADD COLUMN ... DEFAULT <expr>` without an update grant on the table: it is
    judged as DuckDB's own ADD COLUMN and UPDATE, so it needs `update` as well as `alter`.
  Residuals, not refusals: a constant argument DuckDB folds while binding is evaluated before a
  refusal (no value reaches the client), and a wildcard grant (`*.*.*`) never covers a federated
  catalog (grant it by its alias).

- **DESCRIBE, SHOW, SUMMARIZE and computed file names keep working for tenant users.** The gateway
  answers a tenant user's `DESCRIBE <t>`, `SHOW <t>`, `PRAGMA table_info('<t>')` / `show`,
  `SUMMARIZE <t>` and `SHOW TABLES` with equivalent SELECTs over the filtered listings plus a read
  of the object, so they need a read grant on it and honor row filters and masks. DESCRIBE's `key`
  and table_info's `pk` are NULL; on an OPA tenant these answers are empty and SUMMARIZE is refused
  (the listings are narrowed by QoD grants). A file location computed from constants
  (`read_csv('s3://b/in/' || strftime(today(), '%Y%m%d') || '.csv')`, `COPY t TO (<expression>)`)
  is evaluated by the node as the user and judged as the literal it yields (at most 4 KiB). Inside
  a native front door transaction the evaluation runs on the transaction's own link, and a failing
  one (or a NULL or non-text value) aborts the transaction as DuckDB would. Under the FlightSQL
  prepare probe a computed location and a SUMMARIZE are evaluated twice. SUMMARIZE over a
  row-filtered table, refused before, now runs through the filter.

- **Security: BREAKING: files by path only where the user may reach, with S3 keys of the user's
  own.** A tenant user (`*.*.* ALL` included, the ACL on or off) reads or writes a file by path only
  through a file reader or `COPY`, and only:
  - under an `s3://` scope they registered keys of their own for: `qod profile s3 add <scope>
    --access-key-id ... --secret-stdin` (or `--secret-prompt`; `--session-token-stdin`,
    `--endpoint`, `--url-style`, `--region`), `qod profile s3 list|remove`, REST `POST
    /api/profile/s3/{list,add,remove}`, the profile page. The node signs that user's requests with
    those keys only, as connection secrets of that user's own connection, fetched per connection
    from the manager (`POST /api/internal/authz/credentials`). A tenant admin lists and removes
    their users' keys (`qod user s3 list|remove`, `POST /api/user/s3/{list,remove}`, the admin user
    view) but never adds one; MCP has `list_my_s3_credentials` and `remove_my_s3_credential` only.
    At most 32 per user; secrets are write-only and never in a manifest (users enter them again
    after an import); audit actions `user.s3.add` / `user.s3.remove`. An endpoint other than AWS's
    must be listed in `QOD_USER_S3_ALLOWED_ENDPOINTS` (`quack-on-demand.userS3.allowedEndpoints`,
    empty = AWS only). New table `qodstate_user_s3_credential` (Liquibase `0052`);
  - or under a local folder the tenant authorized: `qod tenant location add <tenant> <folder>`,
    `list`, `remove`, REST `POST /api/tenant/location/{list,add,remove}`, MCP
    `list_tenant_locations` / `add_tenant_location` / `remove_tenant_location`, the tenant page,
    manifest `tenants[].locations`; superusers and the tenant's admins; the folder is absolute and
    under `<QOD_TENANT_FILES_ROOT>/<tenant id>/` (`quack-on-demand.tenantFiles.root`; unset, the
    default, authorizes no local folder). New table `qodstate_tenant_location` (Liquibase `0051`).
    Windows nodes admit no local folder.
  A database's data folder, any tenant's, is never reachable (a scope at or under one is refused
  when added, `400 scope_in_data_folder`). Any other scheme (`http(s)`, `gs`, `az`, `r2`) is
  refused. Before, file access was superuser-only while the ACL validated and open to everyone with
  the ACL off. Users' keys travel from the manager to the nodes signed, not encrypted: keep
  manager-to-node traffic on a private network. They are stored in plaintext in the control-plane
  database (`qodstate_user_s3_credential.secret_access_key` and `session_token`), so its backups and
  replicas carry every user's keys: protect them accordingly. Operator action: set `QOD_TENANT_FILES_ROOT` and
  `QOD_USER_S3_ALLOWED_ENDPOINTS` as needed, and tell tenant users that loading their own files now
  starts with `qod profile s3 add`.

- **Security: BREAKING: the nodes' own S3 credentials no longer sign users' requests.** Nodes open with
  DuckDB's external file cache and HTTP metadata cache off (both are shared across connections), no
  longer hold the unscoped `quack_s3` secret (the data path's secret is scoped to the data path and
  its branches and names every key), reset the S3 settings httpfs copied from `AWS_*` /
  `DUCKDB_S3_*` environment variables, and turn `aws_default_credentials` off. Non-superusers may
  not create or drop secrets, change those settings, or `ATTACH`. So the manager's `QOD_S3_*` keys
  (or the instance's credential chain) now sign only a database's own data path: a superuser read
  of another bucket, and a trusted initSql or `sql` federation setup that read one with the node's
  keys, now finds no secret. Operator action: superusers register their own keys (`qod profile s3
  add`) or create a scoped secret; an initSql that reads other buckets needs its own `CREATE
  SECRET`.

- **Iceberg tables on S3 are read-only through QoD for every principal, superusers included.** A
  catalog that neither vends credentials nor has AWS credentials of its own now reads its tables
  with each reading user's own S3 keys, which qod-node installs as connection secrets, and DuckDB's
  Iceberg extension (v2.0.0) commits its manifest files without connection secrets, so a write
  fails with HTTP 403 at commit, a superuser's too. This lasts until upstream duckdb-iceberg signs
  its commits with connection secrets. The raw files
  of a warehouse bucket are not data folders QoD knows: a user holding keys for it can read them
  directly.

- **BREAKING: extension settings are fixed when a node starts.** `allow_community_extensions`,
  `allow_unsigned_extensions` and `autoinstall_known_extensions` are false on every node, set before
  its database opens, so no init SQL or session can turn them on. To allow one, set
  `QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS`, `QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS` or
  `QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS` to `true` on the manager (`quack-on-demand.nodeExtensions`;
  the Helm chart has no value for them, use its `env`); it applies at each node's next start. A
  statement that relied on DuckDB downloading a known extension now fails: install it at node start
  (`INSTALL <name>;` in a database or pool `initSql`). Nodes install httpfs and avro themselves,
  before the database's `initSql`, so an `extension_directory` or `custom_extension_repository` set
  there does not apply to those two (an air-gapped mirror, or a pod whose HOME is fresh at every
  start, fetches both from DuckDB's default repository); quack installs after the trusted init
  statements that set those, before a lockdown pool restricts the file system. Every node also runs
  with DuckDB's `remote_pushdown` optimizer off (restored after the init SQL if it dropped it).

- **Security: BREAKING: a tenant admin's `initSql` runs under an allowlist.** A database's or pool's
  `initSql` written by a superuser (or by the manager: `qod serve`, a manifest import, a branch) runs
  as written; one written by a tenant admin runs statement by statement on the node and keeps only
  `SET` / `RESET` of 15 resource and session-default settings to a literal (`memory_limit`,
  `threads`, `timezone`, ...), read-only PRAGMAs, and `INSTALL` / `LOAD` by bare name of 21 DuckDB
  extensions; every other statement is skipped (the node log names its position, never its text).
  Such a write is refused up front with `400 init_sql_refused` naming each refused statement's
  position and reason; where the manager cannot check it (Kubernetes, fleet without
  `QOD_FLEET_EPHEMERAL=local`) it is accepted with an `initSqlWarning`. A tenant admin's text holds
  at most 64 KiB, and no initSql may contain `qod:untrusted-`. Responses carry `initSqlTrusted`;
  manifests carry it too. New: `POST /api/pool/setInitSql`, `qod pool set-init-sql`, MCP
  `set_pool_init_sql` and the pool page's editor, under the same rule. Upgrade: Liquibase `0053`
  marks every non-empty initSql not written by `qod serve` untrusted, and boot WARNs once per
  database or pool that would lose statements; a superuser re-saving the text unchanged (`qod
  database update --init-sql ...`, `qod pool set-init-sql ...`) trusts it again. Restart the nodes
  after that.

- **Security: BREAKING: `sql` federation sources are superuser-only.** Creating or changing a
  federated source of type `sql` (free-form `setupSql`) answers `403 superuser_required` to a tenant
  admin on every path (REST, MCP, CLI, manifest import); typed sources (Postgres, MySQL, Iceberg
  REST) are unchanged. Existing `sql` sources keep running, and boot WARNs once per one whose author
  is unknown (a superuser re-saves it unchanged, or deletes it). Every `{{secret.NAME}}` in a `sql`
  template must now sit inside a plain single-quoted literal: a save otherwise answers `400
  invalid_federated_source` naming the placeholder (`E'...'`, dollar quoting and `$` outside literals
  are refused as well, since they are not judged). A template stored before keeps an unquoted
  placeholder only when its value is a plain token (letters, digits, `_`, `.`, `-`) that no quote or
  letter touches, such as a port; otherwise that source is left out of the node's setup with a WARN,
  and boot lists each such template. Operator action: quote the placeholders of those templates
  and save them again as a superuser. A database's `objectStore` values and an Iceberg REST
  source's fields now refuse line breaks when they are written.

- **BREAKING for fleet mode and hand-written Kubernetes manifests: nodes call the manager back.**
  Every handle carries the URL of the replica that minted it (`QOD_INTERNAL_URL`,
  `quack-on-demand.nodeAuthz.internalUrl`), which nodes call for their verdicts. Its default is
  `http://<QOD_POD_IP>:<port>` when the pod IP is set, else `http://<QOD_ON_DEMAND_HOST>:<port>` when
  the manager binds one specific address, else `http://127.0.0.1:<port>`. The manager now refuses to
  boot:
  - in fleet mode without `QOD_INTERNAL_URL`: set it next to `QOD_RUNTIME_TYPE=fleet` and
    `QOD_FLEET_JOIN_TOKEN`, to this replica's own plain `http://` address on the servers' private
    network (under HA one per replica, never a load balancer); `docker-compose.fleet.yml` sets
    `http://host.docker.internal:20900`;
  - on Kubernetes with a loopback decide URL: the Helm chart sets `QOD_POD_IP` from the downward API
    (and fails on `env.QOD_POD_IP`, or on `env.QOD_INTERNAL_URL` with more than one replica); a
    hand-written manifest must set `QOD_POD_IP` from `status.podIP`, or `QOD_INTERNAL_URL`;
  - on any runtime with a URL qod-node cannot call (not lowercase `http://`, user info, a query,
    a bad port).
  Boot prints `node decide URL: <url>`. With `networkPolicy.enabled`, the chart's policies now let
  node pods reach the manager pods on TCP 20900 whatever `networkPolicy.ingressFrom` says (the whole
  REST port: a network policy cannot filter by path), in the release namespace only. A node host
  name in `QOD_INTERNAL_URL` is resolved once per 30 s by each node. Fleet servers must run this
  release's `qod fleet join`: the assignment's `dbInitSql` is gone (the manager's resource SETs are
  `resourceSql`, the tenant's initSql rides its own secret files with its trust), and an older join
  process would run a node without it.

- **Manager modules must be recompiled against this core** (`Tenant` gained a field, and
  `AuthzRequest`, `ExecCaller` and `PoolSupervisor.authorizeHandshake` take
  `superuserAdmissible` with no default: a module that builds one or calls it passes it).
  `ManagerContext.scopeOf` resolves every credential to a `SessionScope`, and an absent or
  unresolved one is `NoAccess`, never trusted.

- **Upgrading:** mint PATs for automation before upgrading (`qod login`, then
  `qod auth pat create`), point MCP clients and SCIM connectors at them, then remove
  `QOD_API_KEY` from every environment, chart value and Compose file. In fleet mode set
  `QOD_INTERNAL_URL` on every manager and upgrade `qod` on every server; on Kubernetes without the
  chart set `QOD_POD_IP`. Grant tenant users what they read before relying on `QOD_ACL_ENABLED=false`
  (it no longer opens tables), set `QOD_TENANT_FILES_ROOT` / `QOD_USER_S3_ALLOWED_ENDPOINTS` if users
  load files, review the boot WARNs about untrusted initSql and `sql` federation sources (a superuser
  re-saves what should stay), and restart every node. Under HA, set `QOD_NODE_KEY_SECRET` the same on
  every replica or leave it unset on all.

## 0.10.1

- **Fleet errors state the facts and suggest the fix.** A refused heartbeat or fleet admin call now
  says what the manager sees, not only what it refused: `address_change_refused` names the
  registered and the reported address and whether the server is drained or holds a node;
  `source_change_refused` and `approval_unbound` name the approved and the current source and the
  `QOD_FLEET_AUTO_APPROVE` list; `server_active` gives the server's liveness, node and last
  heartbeat. Each error ends with the commands that fix it and where to run them, on the manager
  or on the server. The error body gains optional `details`, `actions` (alternatives, each a
  `where`, a `command` and an optional `note`) and `retryable` fields, left out when unset, so
  every other error body is unchanged. The join token, passwords and SQL never appear in one.

- **Every `qod` command prints the manager's fix under an error,** as a `to fix:` block (`to fix
  (any one):` when there are alternatives); the admin UI shows the same block under a failed
  action.

- **`qod fleet join` stops on a misconfiguration no retry can fix.** Before its first successful
  heartbeat, an `https://` URL pointed at the manager's plain-HTTP REST port, a wrong join token, a
  URL with no fleet endpoint, or a name registered with another address now print the fix and exit
  with code 2 instead of retrying every 5 seconds forever. After a successful heartbeat it never
  exits on a refusal: it keeps its node running and retries. A failure that repeats prints one
  `still failing (<code>, N times), retrying` line instead of the full message each time, and a
  network failure leads with the explanation, the raw TLS or socket error last. The sample systemd
  unit gains `RestartSec=5` and `StartLimitIntervalSec=0`, so a join that exited is re-run.

- **Pending fleet slots and failed nodes explain themselves.** A pool waiting for a server carries
  `pendingDetail`, e.g. `3 servers: 1 pending approval (b), 1 drained (a), 1 busy (c): qod fleet
  approve b, or qod fleet undrain a, or join another server`, or the memory a slot needs against
  the largest free server; the UI shows it on the pending badge. `qod fleet join` recognises the
  common node start failures (port in use, DuckDB extension download blocked, catalog Postgres
  unreachable, duckdb missing) and reports a suggested fix, shown as `nodeAction` in `qod fleet
  servers` and the servers page. Liquibase `0046` adds the nullable `node_action` column to
  `qodstate_fleet_heartbeat`; it is applied at boot and safe under a rolling HA upgrade.

- **Fleet docs point `--manager` at the TLS proxy.** The examples used `https://<host>:20900`, the
  manager's own plain-HTTP port, which fails the TLS handshake. They now use the proxy URL
  (`https://mgr`); `http://<host>:20900 --insecure` stays the test-only form.

- **Manager modules must be recompiled against this core** (source-compatible, but the
  `ErrorResponse` constructor gained fields).

- **Release announcements on Discord are a digest.** The #news post lists each changelog entry's
  title and links to the full section at the release tag, in at most three messages, instead of
  the whole section: 0.10.0's 70 KB section hit Discord's rate limit partway and left a cut-off
  post. A rate-limited post is now waited out and retried.

## 0.10.0

- **Fixed: the metadata filter reads a system-catalog subquery inside parentheses.** A predicate
  such as `WHERE (x OR EXISTS (SELECT ... FROM information_schema.tables))` was denied ("a position
  this filter cannot rewrite"): jsqlparser 5 reads `(expr)` as a parenthesized expression list,
  which the filter's walk did not descend. It now filters the subquery like an unparenthesized one.
  Nothing leaked before: the filter's reference count denied the statement.

- **Fixed: the demo's federated source attaches on a default `run-jar.sh` launch.** The `acme_pg`
  source of `globex_tpcds` reads its connection from the manager's `QOD_PG_*` variables, which
  `run-jar.sh` and `run-jar.ps1` defaulted for their own Postgres probe without exporting, so a
  launch that did not set them logged "federation blob resolution failed" and the globex nodes
  booted without `acme_pg`. Both launchers now export host, port, user and password, defaults
  included; nodes still never inherit them. The demo source connects as the control-plane login:
  keep node lockdown on (`QOD_NODE_LOCKDOWN=true`) wherever the demo is reachable by others.

- **jsqlparser 5.4.162 and jsqltranspiler 1.16.** The SQL parser behind the ACL, column and row
  policies, the metadata filter and the write screens moves from 5.4.15, which changed under them:
  - A batch is now split where DuckDB splits it (at each `;` its lexer treats as a separator) and
    every statement is parsed on its own. The parser stopped returning a batch's first statement
    and stopped skipping a statement it cannot parse inside a batch, which would have denied any
    batch holding one (`USE mem.main; SELECT ...`) and let a `SHOW TABLES` after such a statement
    through the metadata filter unfiltered. Column and row policies still rewrite only a batch's
    first statement; the protected-write guard analyses every statement before them, as before.
  - `TABLE t` is read as a statement wherever it appears (`x = ANY (TABLE t)`, `IN (TABLE t)`,
    `EXISTS (TABLE t)`, `SELECT (TABLE t)`, `FROM (TABLE t)`). The ACL checks it as a read of `t`,
    and it is refused under a column policy (always in expression position, when `t` has one in
    table position) and under a row policy on `t`. Before, the ACL refused the statement form and
    the column policy rewrite missed `= ANY (TABLE t)`, which read every column of `t` unmasked.
  - Star modifiers are expanded from the parser's new transformer list: a column-list EXCLUDE /
    EXCEPT and REPLACE as before; RENAME, APPLY and a pattern EXCLUDE are refused under a column
    policy.
  - The write screen judges a statement that fails to parse on its original text too, not only on
    the parser's rendering of it.
  - jsqltranspiler 1.16 resolves `TRIM(x)` and a select-list `LIKE` again (1.14 and 1.15 threw on
    their absent optional parts, which denied them under column policies).

- **DuckDB 2.0.** Engine, nodes and JDBC move to DuckDB v2.0.0 (`org.duckdb:duckdb_jdbc:2.0.0.0`
  from Maven Central) and Quack protocol v3, with duckdb-quack at `f96420ed`, the commit the v2.0.0
  quack extension ships. All six libquackwire natives (linux-x86_64, linux-aarch64, osx-x86_64,
  osx-aarch64, windows-x86_64, windows-aarch64) are built against the release. DuckDB 2.x renamed its release assets: `run-jar.sh`,
  `run-jar.ps1`, the qod CLI and the images download `duckdb-cli-<platform>.tar.gz` and
  `duckdb-shared-libs-<platform>.tar.gz` (1.x shipped `.zip`s), so they need `tar` instead of
  `unzip`.
  BREAKING: the manager and every node upgrade together (restart every node). The native front
  door requires DuckDB 2.0 clients; a 1.x client gets "requires a DuckDB 2.0 client". It also
  admits a 2.0 client's `BEGIN TRANSACTION` and its INSERT data path (`scan_data_from_quack_client`,
  scoped to the client's own connection). DuckLake catalogs older than the latest version are
  migrated once by the manager's DuckLake initializer, under its advisory lock, to DuckLake 1.1
  (a catalog a 2.0 pre-release created at `1.1-dev1` included). Each migration is recorded in a
  `qod_meta.ducklake_migration` table in the catalog's database, and a current catalog attaches
  with no DDL, so a boot no longer re-runs the migration DDL (ACCESS EXCLUSIVE in Postgres)
  on every catalog. The initializer waits at most 5 s for a catalog table lock: when a long-running
  read holds one, it logs a WARN naming the database and retries on the next reconcile pass instead
  of stalling boot. Nodes, loaders and the demo seed attach as-is. The migration is one-way: back up catalog databases
  before upgrading, there is no downgrade.
  Upgrade, HA (`replicaCount > 1`): there is no rolling upgrade across this release. A manager on
  the previous version cannot reach a 2.0 node and a 2.0 manager cannot reach a 1.5.6 node, so
  during a rolling `helm upgrade` every statement routed across versions fails, and a 2.0 replica
  promoted to leader migrates catalogs that 1.5.6 nodes still serve. Back up the catalog databases,
  scale the manager to one replica and stop writers, upgrade it, restart every node once no
  previous-version replica is left, then scale back up.
  A DuckDB 2.0 client on the native front door gives up on a statement after its `http_timeout`
  (default 30 s); run `SET http_timeout = <seconds>` in the client session before a longer one
  (FlightSQL is unaffected). `VARIANT` columns reach FlightSQL clients as the Arrow
  `arrow.parquet.variant` extension type, a struct of two binary fields to a client that does not
  know it: cast to JSON for those clients.
  Fixed: native front door catalog sync on multi-node pools. A DuckDB client attaching a pool with
  more than one read-capable node could get an attached catalog with every schema and no table
  ("Table with name ... does not exist!"): its schema and table listings, joined on node-local
  oids, were routed to different nodes. A session's catalog reads now stay on one node. A FlightSQL
  Execute's soft pin to its Prepare node now also falls back to normal routing when that node is
  unhealthy, draining, quarantined or at its `maxConcurrent` cap.

- **Security: boot SQL can no longer run OS commands on the node host.** Database and pool
  `initSql`, and a `sql` federated source's `setupSql`, may no longer contain a line whose first
  non-blank character is `.`: the DuckDB CLI runs such a line from its `-init` channel as a
  dot-command, and `.shell` / `.system` ran an OS command as the node's OS user (the manager's user
  on the local backend, the join process's on a fleet server), so a tenant admin could reach the
  host and the control-plane secrets. Every write path (REST, CLI, MCP, manifest import) answers
  400. The manager also refuses to start a node whose resolved boot SQL holds one (this covers
  `{{secret.NAME}}` values spliced in at spawn and rows stored before this release, which now fail
  their spawn until fixed), and both spawn scripts refuse it too, so Kubernetes pods and fleet
  servers are covered.

- **Security: a MERGE `THEN DELETE` condition can no longer read past grants and policies.** The
  subquery in `WHEN MATCHED AND <cond> THEN DELETE` and `WHEN NOT MATCHED BY SOURCE AND <cond>
  THEN DELETE` was left out of the statement's table reads, so the QoD ACL, the OPA input and the
  column-mask / row-policy write guard never saw it: a tenant user who could write any table read
  a table they had no grant on, or the real values behind a mask or row filter (one value at a
  time through the deleted row count, or whole through an `error((SELECT ...))` message). That
  subquery is now checked like the `THEN UPDATE` / `THEN INSERT` forms, and an unrecognized MERGE
  action is refused. The earlier write-guard fix covered MERGE only in those two forms.
- **Security: one deeply nested statement can no longer crash the manager.** A statement nested
  deeply enough (`SELECT 1+1+...+1` with tens of thousands of terms, or hundreds of nested
  subqueries) overflowed the recursive SQL analysis and escaped as a `StackOverflowError`, which
  cats-effect treats as fatal: any authenticated user halted the whole manager, for every tenant.
  This reached the default configuration (routing reads the statement's tables even with the ACL
  off) as well as the ACL, OPA, lockdown, column-mask, row-policy write guard and filtered-metadata
  steps. Every analysis step now catches the overflow and refuses the statement fail-closed ("nested
  too deeply to analyse"); with the ACL off and no policy in play it is forwarded unanalysed, and the
  node answers it.
- **Security: a result column with no Arrow mapping no longer leaks manager memory.** A statement
  returning DuckDB's `TYPE` (`SELECT get_type(42)`, `make_type(...)`, `TRY_CAST(... AS TYPE)`, or
  a list, struct or map holding one) failed when the manager built its Arrow reader, and the node's
  imported result was never freed: every such statement leaked it for the life of the process, so
  any pool user, even one with no table grants, could grow the shared manager until it was
  OOM-killed. FlightSQL queries and schema probes, MCP and REST previews were all affected; the
  native front door was not. The result is now freed, and the statement fails with "a result column
  of type TYPE has no Arrow mapping and cannot be returned; cast it to VARCHAR".
- **Security: a masked column can no longer be read raw by shadowing or aliasing its qualifier.**
  DuckDB binds `q.name` to the innermost binding `q` only when it has the column; otherwise it reads
  column `q` and takes its field `name`, and failing that climbs to the enclosing query. A holder
  of a column mask or deny policy read the protected values raw both ways: a correlated shadow
  (`SELECT (SELECT x.c_email FROM (SELECT 1 AS a) x) FROM customer x`, the inner `x` a derived
  table, CTE, VALUES or base table lacking the column) and a struct field through an alias named
  like the masked column (`SELECT c_addr.city FROM customer AS c_addr`, also three-part, method
  call, WHERE and ORDER BY forms, and `SELECT *` over a DuckLake table with that alias). Both
  forms, in every clause and both unresolved-table modes, are now refused. A qualifier that names
  a covered column is refused even where DuckDB would bind it to the table (`SELECT c_addr.c_id
  FROM customer AS c_addr`): alias the table differently.
- **Fixed: a FlightSQL `executeUpdate` whose COMMIT fails is no longer acknowledged.** On a
  DuckLake pool a write runs inside a stamped transaction whose COMMIT is deferred until its result
  is read. The DoPut path (JDBC `executeUpdate`, and the prepared-update calls of Arrow JDBC, ADBC
  and DBeaver) read only the row count, closed the result and then acknowledged it, so a COMMIT
  that failed there (a DuckLake write-write conflict with a concurrent writer, a node stopped right
  after the statement, a lapsed lease) was logged at WARN and swallowed: the client got its row
  count for a write the node had rolled back. The result is now read to the end, committing,
  before the count is acknowledged; a conflict fails the update with a retryable UNAVAILABLE ("retry
  the statement"), any other COMMIT failure with INTERNAL, as DoGet already did. A COMMIT failure
  on a stream nobody reads to the end is now logged at ERROR. Statement history and the write audit
  still record such a statement as `ok`.
- **Fixed: a FlightSQL batch led by a read no longer runs twice.** To answer a Prepare,
  GetFlightInfo or GetSchema with a result schema, the edge ran the statement on a node; for a
  batch (`SELECT 1; INSERT INTO orders VALUES (1)`, `SET VARIABLE v = 1; INSERT ...`, any lead
  but a write) and for a single `USE`, `SET`, `CALL` and other non-query statements it ran the
  whole text, so the write committed at prepare time with no history row or audit, and the Execute
  ran it again: every such row was written twice (one ADBC `cursor.execute` or `qod sql "..."` is a
  Prepare plus an Execute), and a lone GetSchema wrote with no trace at all. A batch, and every
  non-query statement but a lone `PRAGMA`, now reaches a node only when executed, once; its result
  schema is advertised as absent and read from the result stream, which the ADBC driver accepts. A
  client that reads the schema before executing (an ODBC driver behind Power BI) gets none for
  these statements.
- **Security: a batch led by a read can no longer launder a masked or filtered table.** A
  statement is classified by its first statement only, so `SELECT 1; INSERT INTO scratch SELECT
  c_email FROM customer` counted as a read: the protected-write guard let it through, the column
  and row rewriters (which parse only the first statement) left it unchanged, and the node ran the
  INSERT, copying the raw masked values or unfiltered rows into a table the holder of a column or
  row policy could read back. A batch of more than one statement from such a principal now has
  every statement analysed, whatever leads it: a write reading a protected table is refused as a
  single one is, and so is a plain read of a protected table after the first statement ("cannot be
  read in a multi-statement batch; send that query on its own"), which the rewriters never saw.
- **Security: a CTE shadowing a table can no longer launder a masked column through an INSERT.**
  In `WITH leak AS (SELECT c_email FROM customer) INSERT INTO leak SELECT * FROM leak` the
  protected-write guard checked the INSERT's SELECT without its leading WITH, so `leak` resolved to
  the physical, unpoliced table and the statement was allowed, while DuckDB read the CTE and wrote
  the raw masked (or `deny`-policied) values into `leak` for the policy holder to read back. The
  guard now checks the SELECT together with the statement-level WITH, so that INSERT is refused
  like `INSERT INTO leak SELECT c_email FROM customer`; a CTE reading only unmasked columns is still
  allowed. An INSERT carrying a WITH both before `INSERT` and inside its SELECT is refused when it
  reads a masked table.
- **Security: BREAKING: column masks and row filters hold for `<catalog alias>.table` with the ACL
  off.** DuckDB reads `acme_tpch.customer` as catalog `acme_tpch`, current schema, so it is the same
  table as a bare `customer`, but the mask and row-filter checks read `acme_tpch` as a schema and
  matched no policy. With `QOD_ACL_ENABLED=false` (the default outside `qod serve`), a user holding
  a column or row policy could copy the raw masked values or the filtered-out rows into a table of
  their own (`CREATE TABLE mine AS SELECT c_email FROM acme_tpch.customer`), and a user holding a
  row policy could read every row with a plain `SELECT * FROM acme_tpch.customer`. The same held for
  a federated alias (`pg_src.customer`). For a user holding a column or row policy, a two-part name
  whose first part is a catalog attached on the pool is now refused whatever the ACL setting, as the
  ACL already refuses it when on: "ambiguous two-part name 'acme_tpch.customer': 'acme_tpch' is an
  attached catalog; qualify fully as 'acme_tpch.<schema>.<table>'". Users without a policy are not
  affected; a user with one writes `customer`, `main.customer` or `acme_tpch.main.customer`.
- **Security: row filters hold for PIVOT sources and EXPLAIN.** The row-policy rewrite wraps a
  table in the positions it knows (FROM, JOIN, FROM-first, a parenthesized TABLE statement) and
  passed everything else through unchanged, so `SELECT * FROM (PIVOT customer ON ...)`, in any
  position (a CTE body, a scalar, EXISTS or IN subquery, beside a filtered read of the same table),
  pivoted every row, and `EXPLAIN ANALYZE SELECT ... FROM customer` ran its query unfiltered,
  leaking hidden rows through row counts and error messages. A row policy holder's statement is now
  refused when a table their policy covers is read anywhere the filter cannot reach ("pivot a
  subquery instead: PIVOT (SELECT * FROM t) ..."), and an EXPLAIN of a statement reading such a
  table is refused. A pivoted subquery is filtered as before.
- **Security: node lockdown judges EXPORT DATABASE like COPY ... TO.** EXPORT DATABASE writes
  `schema.sql`, `load.sql` and one file per table to its target directory, but no lockdown rule
  looked at it: with the ACL off, a tenant user on a locked-down pool could write to the node's
  local disk (on a pool with a local data path) or into the denied DuckLake data bucket, both of
  which `COPY ... TO` is refused. Its target is now judged as a COPY path is: a local path or a
  denied bucket is refused, another object-store URL is admitted.
- **Security: a PREPARE named by a string no longer hides the statement it prepares.** DuckDB
  takes a string as a prepared statement's name (`PREPARE 'p' AS ...`, and `E'p'`, `$$p$$`,
  `$t$p$t$`), but the screens skipped a `PREPARE <name> AS` head only for an identifier name, so
  they judged the word `PREPARE` and admitted `PREPARE 'p' AS COPY (...) TO '/tmp/x.csv'; EXECUTE
  p`: a node-local file write and a write into the denied DuckLake bucket under lockdown, and,
  with the ACL on, a COPY to any path or URL for a tenant user holding the wildcard ALL grant. The
  prepared statement is now judged whatever the name's form.
- **Fix: maintenance no longer deletes files a live branch still reads.** A branch reads its
  parent's Parquet files by absolute path. The parent's merge step folds small files into one,
  deleting their catalog rows and scheduling them for cleanup, and a later cleanup deleted them:
  the branch could no longer scan its pre-fork data, and its unmerged work could no longer be
  merged. The pinned-file check meant to prevent this could never match. While a database has a
  live (open or proposed) branch, maintenance now skips the merge and cleanup steps and records the
  run `partial`, saying why; they resume once the branch is merged, discarded or expired. The
  pinned-file check is removed, and a run's `filesCleaned` now counts the files cleanup actually
  deleted rather than every file scheduled for deletion (files inside the grace window stay
  scheduled and used to be counted too). A DuckLake `CHECKPOINT` run by a superuser still merges
  and cleans up on its own.

- **Security: BREAKING: CHECKPOINT and DuckLake maintenance are reserved to superusers.** Every
  CHECKPOINT form (`CHECKPOINT`, `CHECKPOINT <catalog>`, `CALL checkpoint(...)`, `FROM
  checkpoint(...)`, behind `EXPLAIN` / `PREPARE`) and the destructive DuckLake maintenance and
  retention calls (`ducklake_merge_adjacent_files`, `ducklake_rewrite_data_files`,
  `ducklake_flush_inlined_data`, `ducklake_expire_snapshots`, `ducklake_cleanup_old_files`,
  `ducklake_delete_orphaned_files`, `ducklake_set_option`, and the bare or catalog-qualified
  `merge_adjacent_files()` / `set_option(...)` macros) are refused to tenant principals in every ACL
  mode, and to every caller under lockdown, like `FORCE CHECKPOINT`. Before, a tenant user could run
  DuckLake's whole maintenance chain (the default `qod_all_tables` role covered it, and with the ACL
  off nothing checked it) and delete parent files a live branch or time travel still reads. QoD's
  own maintenance and branch merges do not go through the edge and are unaffected. DuckLake's other
  per-schema macros (`snapshots`, `table_info`, `table_changes`, `table_insertions`,
  `table_deletions`, `current_snapshot`, `last_committed_snapshot`, `options`, `settings`,
  `set_commit_message`) are now gated like the `ducklake_*` family whenever the ACL validates.
  CHECKPOINT classifies as DDL (it joins the default `QOD_CLASSIFIER_DDL` list), so a superuser's
  reaches a writer and is stamped and audited.

- **Security: `histogram()` / `histogram_values()` with a computed source no longer reach the
  DuckLake metadata catalog.** These table macros run `query_table(source)`, so a source built at
  run time (`'__duck' || 'lake_metadata_<alias>.ducklake_data_file'`) passed the metadata-catalog
  screen in the default ACL-off posture and returned the per-file encryption keys. A non-constant
  source in a table position is now refused to non-superusers, and a constant one is screened for
  the metadata prefix; the `histogram(col)` aggregate is unaffected.

- **Security: a backtick-quoted table or column no longer defeats column masks and row filters.**
  On DuckDB 2.0 a backtick quotes an identifier, and the access checks read it as one, but the
  policy rewriters only unquoted double quotes: `` FROM `orders` `` skipped the row filter and
  `` upper(`email`) `` read a masked column raw (and a CTAS laundered it into another table). One
  shared unquote now serves every policy site, and the edge renders backtick identifiers as double
  quoted before the checks. A double-quoted masked column inside an expression (`upper("email")`) is
  now masked instead of refused.

- **Security: the spawn script's stale-directory sweep no longer trusts shared `/tmp`.** It now
  considers only real directories owned by the current user, never follows a symlink (nor chmods
  through a symlinked `secrets`), deletes a recorded spill directory only when it is a `qod-node.*`
  directory directly under the host's spill root, treats another user's live process as alive, and
  can no longer abort a spawn. Before, any local user sharing `/tmp` with a bare-host manager or
  fleet server could make the next node spawn `rm -rf` an arbitrary path and block every later
  spawn. The PowerShell twin gets the same hardening.

- **Security (Helm chart): node lockdown is on by default, and `postgres.user` has no default.**
  With the chart's former defaults (SQL ACL off, lockdown off, `postgres.user: postgres`), any
  tenant user could `read_text` the node's mounted `/var/run/qod-secrets/pgPassword`. For a database
  created without its own metastore credentials that is the control plane's Postgres superuser
  password, which opens the control-plane database (every tenant's password hashes, tokens and
  catalog passwords), every other tenant's catalog (including per-file encryption keys) and the
  server itself (`COPY ... TO PROGRAM`). New `security.nodeLockdown` (default `true`, renders
  `QOD_NODE_LOCKDOWN`) and `security.aclEnabled` (default `false`, renders `QOD_ACL_ENABLED`)
  values; `helm install` prints a warning when both are off. Lockdown refuses ATTACH, INSTALL, LOAD,
  protected settings and local file reads to non-superusers; a pool can still opt out with
  `POST /api/pool/setLockdown`, and its tenant users can then read the file again unless the ACL is
  on. Superusers are never screened. BREAKING for chart users: `postgres.user` must now be set; use
  a dedicated non-superuser role with `CREATEDB` that owns the control-plane database (`CREATE ROLE
  qod LOGIN CREATEDB PASSWORD '...'; CREATE DATABASE qod OWNER qod;`), and tenant statements that
  relied on ATTACH, INSTALL, LOAD or local file reads are refused until their pool opts out. This
  does not bound what the credential reaches once read (a superuser, or a pool that opts out, still
  gets the shared login); one Postgres role per catalog database is designed but not implemented.
  The manager's own defaults (`qod start`, `run-jar.sh`, Docker) are unchanged.

- **Fixed: `qod serve --demo`, and `qod start` / `qod serve` with macOS `/usr/bin/java` or on
  Windows, refused to boot ("native Quack client cannot run on this host").** The launcher never
  told the manager where its libduckdb was: the demo did not provision libduckdb at all, SIP strips
  `DYLD_LIBRARY_PATH` from `/usr/bin/java`, and on Windows the lib directory never reached `PATH`.
  Every launcher path now provisions libduckdb and exports `DUCKDB_CACHE_DIR`, so the manager
  preloads it by absolute path; on Windows the lib directory is also put on `PATH`. When libduckdb
  was found but the native still fails, the boot error says provisioning worked, and its hint names
  the qod CLI cache and `DUCKDB_CACHE_DIR`.

- **Fixed: an upgraded qod CLI kept running the DuckDB it cached for an earlier release.** The
  manager refused to boot, nodes spoke Quack v1, and `qod fleet join` reported the pin rather than
  the engine it ran. The CLI cache is now versioned, `<user cache>/qod/duckdb/<version>/{bin,lib}`
  (the same layout as `DUCKDB_CACHE_DIR`), and `DUCKDB_VERSION` is honoured. Existing qod caches
  are re-downloaded into the versioned layout on first run; the old unversioned `duckdb/bin` and
  `duckdb/lib` directories are no longer read and can be deleted. `qod fleet join` reports the
  version the duckdb binary itself prints.

- **Fixed: `scripts/stop-jar.sh` exited 1 silently, before stopping anything,** when no unmarked
  `spawn-quack-node.sh` wrapper was running (always the case with this release's own wrappers), so
  a manual stop left the manager and its nodes running and `run-jar.sh NUKE=1` wiped state under a
  live manager.

- **Stopping a local manager stops only its own nodes.** BREAKING for anyone scripting
  `scripts/kill-quack-nodes.sh` as a host-wide reset: `stop-jar.sh`, `stop-jar.ps1`, `qod stop` and
  `kill-quack-nodes.sh` now stop only the nodes of the manager on their `QOD_ON_DEMAND_PORT`,
  recognized by the marker `<rest-port>-<boot id>` the manager passes as the spawn script's second
  argument, so another manager's nodes on the same host (an isolated test stack) are left alone. The
  old host-wide reset is `kill-quack-nodes.sh --all`. Upgrading with orphan nodes left by an earlier
  release: their wrappers carry the node token where the marker now goes, so neither the default
  mode nor `--unmarked` matches them and `stop-jar.sh` / `qod stop` do not report them; use
  `kill-quack-nodes.sh --all` (with no manager running). The spawn scripts take
  `<port> [<manager-marker>]`.

- **Typed Postgres and MySQL federated sources.** New source types `postgres` and `mysql` attach an
  external database from typed fields (host, port, database, user, password, plus `sslMode`, and
  `schema` for Postgres), with no SQL to write: REST `connection` on the federated-source create,
  `qod federation create --type postgres|mysql` (`--host`, `--port`, `--database`, `--user`,
  `--schema`, `--ssl-mode`, `--connection`), the MCP `upsert_federated_source` tool, manifests and
  the admin UI. The password is always a source secret (inline or `externalRef`) named by a
  `{{secret.NAME}}` placeholder and is required; the CLI's `--password-stdin` / `--password-prompt`
  and the UI's write-only password field store it for you. The rendered SQL carries the connection
  on a temporary DuckDB secret and attaches with no credential in the ATTACH path (only
  `connect_timeout=10` for Postgres), so the password never shows in `duckdb_databases().path` or
  connection errors, then drops the secret right after the ATTACH so no session can reuse the
  credentials once the ATTACH returns (a re-attach without `READ_ONLY` would otherwise write through
  them); the attached catalog keeps working and reconnects without it. New sources default to
  read-only, enforced by the engine (`READ_ONLY`) and the edge. The SQL ACL and node lockdown are
  both off by default, so while a pool has a read-only catalog (any source type) the edge refuses,
  for every caller, superusers included: `postgres_execute`, `postgres_query`, `mysql_execute` and
  `mysql_query` targeting a read-only catalog or a catalog it cannot read from a string literal;
  `query` / `query_table` with a computed argument; and `json_execute_serialized_sql`. Through them
  a session could end the remote's read-only transaction and write. Calls to writable catalogs and
  constant `query('...')` bodies (judged by the same rule) are unchanged. This screen reads the SQL
  text only: a table macro or view that already wraps a passthrough, created before the catalog
  became read-only (a DuckLake catalog keeps such a macro), and macros or views in attached
  databases are not seen. A database user without write privileges on the remote is the complete
  guarantee; configure one. The attach verifier now watches these
  sources like Iceberg ones (`attachStatus`, per-node failures, retry) and drops the secret when a
  retried attach fails. Manifest import applies the same validation as REST and MCP to every typed
  source (Iceberg included). Fixed with it: a secret value containing `{{` no longer fails the
  tenant-db's whole federation setup (the placeholder check now runs before substitution). A `sql`
  source's setupSql may not mention the reserved secret prefixes `qod_pg_`, `qod_mysql_`,
  `qod_ice_` (judged on the text with `{{alias}}` expanded: it prevents an accidental collision
  with a typed source's secret, not a name spliced in through a `{{secret.NAME}}` value). MySQL is implemented and unit tested but not verified live (the DuckDB 2.0 toolchain
  has no mysql extension), including whether `READ_ONLY` alone refuses writes through it.

- **Security: nodes and the manager no longer load the OS user's persistent DuckDB secrets.**
  DuckDB loads every secret stored under `~/.duckdb/stored_secrets` into an instance on first
  secret use, so a developer's or operator's own cloud keys reached every node (any tenant query
  could use them) and the manager's in-process DuckDB. Every node now points `secret_directory`
  below a read-only marker file in a read-only, private directory of its own (`secrets` in the
  node's working directory: 0500 around a 0400 file; an owner-only ACL denying file creation on
  Windows), before any secret or ATTACH, then puts the secret manager in use so no session can move
  it back. The path can never exist as a directory, so no persistent secret loads and
  `CREATE PERSISTENT SECRET` on a node fails ("File exists"), for root too: use a temporary secret,
  or the database's `objectStore`. If either setup statement fails the node does not start. The
  manager's in-process instances use one such path per JVM, and the `qod serve` demo seeder's DuckDB
  uses it too (and no longer runs `~/.duckdbrc`). Persistent secrets themselves stay enabled, so a
  postgres or DuckLake ATTACH with inline credentials (for example in federation SQL) keeps working;
  turning them off instead would have made DuckDB 2.0 refuse every such ATTACH ("Unknown secret
  storage found").

- **Security: node init SQL no longer lands in `shell_history()`.** On DuckDB 2.0 the CLI records
  every statement it reads from stdin, and the spawn scripts fed the node's whole init SQL that way,
  so `SELECT * FROM shell_history()` on a node returned it: the node token, the catalog Postgres
  password, and the object-store and federation credentials. The init SQL now reaches duckdb through
  `-init` (a FIFO on Linux and macOS; on Windows a current-user-only secrets file that DuckDB itself
  deletes the instant it has read it, named from a no-secrets outer init file), which the CLI does
  not record. The node's stdin is the null device, so no statement or dot-command can be injected on
  the node's own connection. The `-init` channel itself still interprets dot-commands, which is why
  boot SQL is screened for them (see the init SQL entry below). The node is kept alive by a `.shell` dot-command that waits for the
  wrapper process to exit, not by a query: a query pinned a DuckDB transaction (blocking CHECKPOINT
  on a duckdb-file node and holding a catalog Postgres session idle-in-transaction on a DuckLake
  node) and on 2.0 could spin a CPU core for the node's life. The wait also means a node no longer
  outlives a forced kill of its wrapper, best effort: it polls the wrapper's pid, so if the OS hands
  that pid to another process before the next poll, the node lives until that process exits. A crash before the Windows secrets file is read leaves it
  only until a later spawn's sweep, and the wrapper stops a node that fails to delete it. On Windows
  the CLI is only ever handed paths relative to the node's working directory, in ASCII, so a temp
  directory under a profile with an accented or other non-ASCII name (or a quote) works. A failing init statement still does not stop the node. `-init`
  also means a node no longer runs the OS user's
  `~/.duckdbrc`. The CLI only keeps this history in memory when stdin is not a terminal (nothing was
  written to `~/.duckdb_history`), and 1.x CLIs have no `shell_history()`. If you ran DuckDB 2.0
  nodes before this release, rotate what they could read: the catalog Postgres passwords, the
  object-store keys and the federated-source credentials.

- **Nodes run in an empty private working directory.** DuckDB 2.0 reads a file named by an
  unquoted dotted table name when no such schema exists (`SELECT * FROM x.csv` read `./x.csv` from
  the manager's own working directory). Every node, on every runtime (local, Windows, fleet,
  Kubernetes), now starts duckdb in a fresh, empty, per-node directory private to its user, removed
  when the node stops. A relative local dataPath (the bundled `./ducklake/tpch` default is one) is
  resolved against the directory the node used to run in and attached with `OVERRIDE_DATA_PATH`,
  since DuckLake recorded the relative spelling; the override applies to that attach only. Each
  node now spills to its own `temp_directory` (a per-node directory under `/duckdb-tmp` on
  Kubernetes, under `.tmp` of the starting directory elsewhere) instead of a `.tmp` shared by every
  node of the host. An operator's `SET temp_directory` in the database or pool init SQL still wins.
  Branches of a database with a relative dataPath now record the parent's files by absolute path,
  and the merge node attaches the branch by its absolute path. BREAKING: a relative path in an
  operator's database or pool `initSql` or federation SQL (for example `CREATE VIEW ... FROM
  'data/x.parquet'` or `ATTACH 'x.duckdb'`) no longer resolves against the manager's directory,
  because the node no longer runs there; use an absolute path or an object-store URL. Every spawn
  also creates the per-node spill directory under `.tmp/` in the manager's (or `qod start` user's)
  working directory up front, where duckdb previously created `.tmp` only on first spill.
  Upgrading: a branch cloned before this release from a relative-dataPath database cannot read the
  parent's files any more; discard and recreate it.

- **Native client lease.** The manager-to-node native client heartbeats each node connection
  under a lease: `QOD_QUACK_NODE_LEASE_SEC` (default 30, a heartbeat every third, floor 1 s).
  Node connections are released on error and on cancel. New `QOD_DUCKDB_EXTENSION_DIR` points the
  manager's DuckDB at a local extension directory and turns on unsigned extensions.

- **Fixed: the manager leaked 40 bytes per node response.** Every decoded PREPARE and FETCH
  response left its Arrow stream struct allocated in the native client (about 4 GB a day at 1000
  statements per second, plus two per healthy node every health-check interval). The struct is
  freed once Arrow has taken its contents.

- **Fixed: a DuckDB pre-release passed for the release.** `run-jar.sh` and `run-jar.ps1` accepted
  any cached CLI whose version merely contained the pin (`v2.0.0-alpha46057` for `2.0.0`); they
  now require the exact version and reinstall otherwise.

- **The native client is the only manager-to-node client.** BREAKING: the embedded fallback client
  (an in-process DuckDB running `quack_query` over JDBC, behind a JVM-wide mutex) is gone, since
  the DuckDB 2.0 JDBC disables the Arrow export it needed. `QOD_NATIVE_CLIENT` and
  `QOD_NODE_DISABLE_SSL` (which only the fallback read) are removed and ignored if still set, and
  so is the Helm value `quackNode.tls.enabled`. Windows on ARM64, which only ran on that fallback,
  gets its own native (`windows-aarch64/quackwire.dll`, built on GitHub's Windows ARM runner): run
  it with an ARM64 JVM, which `qod start` downloads, and `run-jar.ps1` fetches the windows-arm64
  DuckDB whenever the JVM's `os.arch` is aarch64. A manager whose platform has no bundled
  libquackwire, or whose native cannot load (for instance libduckdb not found), refuses to boot with a message naming the platform, the expected
  `libquackwire/binaries/<platform>/` binary and `DUCKDB_CACHE_DIR`, instead of falling back. The
  demo no longer pins the fallback either.
  Every native now carries a build marker (Quack protocol, DuckDB version and source id,
  duckdb-quack commit). The manager refuses to boot with a "stale libquackwire native" message when
  the marker disagrees with `/native/VERSION` or with the loaded libduckdb, or when the native
  predates markers, instead of booting on a stamp that vouches for a stale binary (on Linux such a
  native used to boot and then kill the JVM with "symbol lookup error" at the first node contact).
  Linux natives link with `-z now`, so a missing symbol fails at load. `verify_quackwire_binaries`
  and `refresh-quackwire-binaries.sh` check every binary's marker, and the refresh takes the
  `quackwire.yml` run of the commit being released (or `QUACKWIRE_CI_RUN=<id>`) instead of the
  latest green run on main. A new `release pins` check, also run before every snapshot and release
  publish (node and worker images included), refuses a pre-release DuckDB JDBC or a `mavenLocal`
  resolver on main.

- **Security: DuckDB 2.0 hardening.**
  - Data-modifying CTEs classify as writes and fail closed in the ACL.
  - Four-part (nested schema) names fail closed.
  - Executing EXPLAIN forms (ANALYZE / ANALYSE, option lists) classify as the explained statement.
  - Lockdown denies `CONNECT` / `DISCONNECT` and extension repository DDL, protects
    `allow_extension_repositories`, `extension_directories` and the other settings that move
    extension, secret or file locations, or redirect the node's outbound connections, and judges
    path-shaped quoted identifiers. It also denies the `enable_logging()` call and protects the
    logging settings (`enable_logging`, `logging_level`, `logging_storage`, `logging_mode`,
    `enabled_log_types`, `disabled_log_types`, `pg_debug_show_queries`), denies the functions that
    run raw SQL on or open a connection to another database (`postgres_query` / `postgres_execute`,
    `postgres_scan` / `postgres_scan_pushdown` / `postgres_attach` / `postgres_configure_pool`, the
    `mysql_*` and `sqlite_*` equivalents) and `getenv` (reads the node's environment, including the
    catalog password), and judges the statement an `EXPLAIN ANALYZE` or a `PREPARE ... AS ...; EXECUTE`
    carries, which DuckDB 2.0 executes.
  - BREAKING: settings that change how a node parses SQL (`dialect_compatibility_mode`,
    `current_dialect`, `active_grammar_extensions`, `allow_parser_override_extension`,
    `preserve_identifier_case`, `show_behavior`, `table_function_identifier_conversion`) and
    `redact_http_logs` / `allow_unredacted_secrets` are refused for every caller, superusers
    included, whatever the ACL mode and the lockdown flag: most are GLOBAL, so one session would
    change how every session on the node is parsed, or expose the node's credentials in its logs.
  - BREAKING: `IMPORT DATABASE` (and its `PRAGMA import_database(...)` form) is refused for every
    caller, superusers included: it replays SQL files the gateway never reads.
  - `postgres_query` / `postgres_execute` and the other raw-SQL / arbitrary-connection functions
    and `getenv` are denied to tenant-scoped principals whenever the ACL validates (QoD-grant and
    OPA modes), the same class as `query()` / `query_table()`. A superuser keeps the raw federation
    passthrough; with the ACL off and lockdown off nothing is blocked, like any other statement.
  - Trigger DDL is reserved to superusers and own-tenant admins ("CREATE TRIGGER is restricted to
    tenant administrators").
  - The edge's comment stripper, statement splitter and classifier share one lexer ported from
    DuckDB 2.0's tokenizer (dollar and E-strings, backtick identifiers, unicode spaces), and
    function calls are detected from its tokens (backtick and double-quoted names).
  - The edge screens lex the SQL before folding case (dollar-quote tags are case-sensitive in
    DuckDB), and the catalog write screen classifies the original statement text.
  - BREAKING: the DuckLake metadata catalog (`__ducklake_metadata_<alias>`, whose
    `ducklake_data_file` / `ducklake_delete_file` hold every per-file encryption key of an
    encrypted database) and the key-returning DuckLake functions (`ducklake_list_files`,
    `ducklake_commit`) are refused to every caller who is not a superuser, whatever the ACL mode
    (QoD grants, OPA, ACL off) and the grants (the built-in `qod_all_tables` `*.*.* ALL` no longer
    reaches them), and to every caller on a pool under lockdown. Any quoting, `USE`,
    `SET search_path`, string arguments and `query()` bodies are covered, and any string literal
    or comment that mentions the name is refused too (an over-deny, the safe direction); a
    run-time-computed target (`query_table('__duck' || 'lake...')`, a `search_path` / `schema`
    set to an expression in any `SET` / `PRAGMA` form, the `postgres_query` / `query` family with a
    non-constant argument) is refused for the same callers. The string-named table carriers
    (`query_table('<name>')`, `query('<sql>')`, `duckdb_table_sample('<table>')`) have their
    literal screened for the metadata catalog and are refused with any non-literal argument.
    `json_execute_serialized_sql` (table function and pragma) is refused outright to the same
    callers: a serialized plan names its catalogs in JSON strings DuckDB decodes after the screen
    reads them. The data tables themselves and the other DuckLake functions are unchanged.
    The screen knows the metadata catalog by its name: a caller who holds the catalog's Postgres
    password and may ATTACH (a tenant user with the ACL off and lockdown off) can attach that
    database again under another alias and read the keys there, so this guarantee also rests on
    that password staying secret.
    String literals are read as DuckDB reads them: a literal continued on the next line
    (`'__duck'` newline `'lake_metadata_x'`, which DuckDB folds into one value) is judged folded,
    in every carrier, `query()` body and `SET` / `PRAGMA` form.
  - Lockdown judges a `COPY` path as DuckDB reads it: a path continued on the next line, an
    escape string or a dollar-quoted string is folded and decoded before the remote and
    denied-bucket checks (a continued path could name a denied bucket, and the other two forms
    were not judged by the COPY rule).
  - `duckdb_table_sample` (new in 2.0; reads the table its string names) and the Iceberg catalog
    functions that act on a table or schema named by a string outside every table grant
    (`iceberg_rewrite_data_files`, `iceberg_rollback_to_snapshot`, `iceberg_to_ducklake`,
    `iceberg_table_properties` / `iceberg_schema_properties` and their `set_` / `remove_` forms) join
    `query_table` in the tenant-ACL class: refused to tenant-scoped principals under QoD grants and
    OPA, never covered by the `*.*.* ALL` wildcard.
  - BREAKING for non-superusers who set these: node-wide settings are reserved to superusers,
    whatever the ACL mode and the grants, and refused to every caller on a pool under lockdown:
    in any `SET` / `RESET` / `PRAGMA` form (glued quotes, behind `EXPLAIN` / `PREPARE`), threads
    (`threads`, `worker_threads`, `external_threads`, `async_threads`), memory and temp space
    (`memory_limit`, `max_memory`, `max_temp_directory_size`, `temp_directory`), the allocator,
    checkpoint / WAL / storage knobs, the shared HTTP client and Postgres pool, logging and
    profiling, extension loading, and every `quack_*` (server limits, `quack_debug_*`), `debug_*`
    and `verify_*` setting; any `SET GLOBAL` / `RESET GLOBAL`; `FORCE CHECKPOINT`; and the
    node-control calls `quack_serve`, `quack_stop`, `quack_cancel`, `quack_identify`,
    `quack_clear_cache`, `pg_clear_cache`, `enable_logging` / `disable_logging`,
    `truncate_duckdb_logs`, `enable_profiling` / `disable_profiling`, `force_checkpoint`. One
    session changing them starved or slowed every session of the pool. Operators set them at node
    start (database / pool `initSql`, `qod pool set-resources`). Session settings a client sends
    (`search_path`, `schema`, `TimeZone`, `Calendar`, `http_timeout`, `enable_progress_bar`,
    `errors_as_json`, `max_execution_time`, `s3_*`, `SET VARIABLE`, ...) are unchanged.
    BREAKING: on DuckDB 2.0 a `SET` / `RESET` / `PRAGMA x = v` without a scope writes a
    GLOBAL-default setting node-wide, exactly like `SET GLOBAL`, so for non-superusers (and every
    caller under lockdown) a bare `SET` of such a setting (`default_order`, `null_order`,
    `default_collation`, `preserve_insertion_order`, `enable_optimizer`,
    `immediate_transaction_mode`, `extra_http_headers`, `ducklake_*`, `pg_staleness_query*`,
    `iceberg_use_metadata_log`, ...) is refused: use `SET SESSION <name>` / `RESET SESSION <name>`.
    Aliases are judged as the setting they name (`force_mbedtls_unsafe` is
    `debug_force_mbedtls_unsafe`), and a setting name the gateway does not know is refused (fail
    closed). The scopes come from the engine source (`scripts/dev/gen-duckdb-setting-scopes.py`,
    regenerated at every pin bump). The `mysql_pool_*` and `mysql_debug_show_queries` settings and
    `mysql_clear_cache()` join the node-wide set. The list is shared with lockdown's protected
    settings (one source).
  - No secret reaches a node process's environment or command line, on any backend: a node user
    with SQL could read `getenv()` or `/proc/<pid>/environ` of duckdb and of its wrapper script.
    The catalog password, the encryption key, the federation and object-store SQL, the node token
    and the object-store credentials now reach the spawn script as files of a private directory
    (`QOD_SECRETS_DIR`, 0700 / 0600, owner-only ACL on Windows) that the script deletes right after
    reading (local and `qod fleet join`), or of a read-only Secret volume (mode 0400) on
    Kubernetes, replacing every `secretKeyRef` env var. Local nodes no longer inherit the manager's
    own credentials (`QOD_API_KEY`, the session JWT secret, passwords). BREAKING for custom node
    images and launchers: the spawn script takes `<port> [<manager-marker>]` (no token argument;
    the token is a file in `QOD_SECRETS_DIR`), ignores secrets passed as env vars,
    and the node image entrypoint no longer reads `QOD_NODE_TOKEN`; restart every K8s node after
    upgrading. On Kubernetes the mounted files stay readable by a node user with unrestricted SQL
    (superuser, or ACL off without lockdown). For a database without its own metastore credentials
    that file holds the control plane's own Postgres login, which opens the control-plane database
    and every tenant's catalog (and the server itself when it is a Postgres superuser); the Helm
    chart now turns lockdown on by default (see the chart entry above).
  - The DuckLake catalog's Postgres password no longer rides an ATTACH string, where
    `SELECT path FROM duckdb_databases()` (and connection errors) showed it to any SQL user of the
    node, who could then reach the catalog and its encryption keys under a name of their own. Nodes
    (both spawn scripts), the merge node's branch attach, the manager's DuckLake initializer, the
    demo seed, the TPC-H / TPC-DS / SSB loaders and the demo federation source put it in a
    temporary `TYPE postgres` secret that the ATTACH names (`SECRET` / DuckLake
    `METADATA_PARAMETERS`) and drop the secret right after attaching, so no session can reuse it.
    Restart every node to pick it up. Federation sources you wrote yourself keep whatever their
    `setupSql` says: use the same pattern for a Postgres or MySQL source.
  - The ACL parser (and the protected-write guard, the metadata filter and the CLS / RLS
    rewriters) reads lexer-canonical SQL: escape strings, dollar-quoted strings, `//` and a word
    glued to a literal could hide a subquery from the ACL while DuckDB read the table. A rewrite
    that cannot be shipped as the caller's statement (it holds `//` or a time-travel clause) is
    refused instead.
  - Time-travel clauses are found on lexer tokens: a clause shape inside a string literal or a
    comment no longer deletes real SQL before the ACL parse.
  - Four-part names also fail closed on `QOD_ACL_DIALECT=generic`.
  - Lockdown denies `query()` / `query_table()` / `json_execute_serialized_sql()` for every caller,
    judges replacement-scan literals at every depth (subqueries, JOIN, comma joins, parenthesized
    table positions), and covers the avro and iceberg readers (`read_avro`, `avro_metadata`,
    `iceberg_scan` and the other `iceberg_*` path readers), the iceberg table-maintenance
    functions and `quack_serve` / `quack_stop`.
  - The ACL gates the table `SUMMARIZE <table>` reads (it was admitted as table-free while
    returning per-column statistics), the query of `DESCRIBE <query>`, and the table named by a
    parenthesized statement in table position (`(SUMMARIZE t)`, `(TABLE t)`, `(DESCRIBE t)`,
    `(SHOW t)`), which used to gate the keyword instead. A string literal in those positions is a
    file reference. The row-policy rewrite refuses a parenthesized statement reading a policy
    table, and the protected-write guard denies `SUMMARIZE` of one.
  - A subquery inside a MAP literal or a `json_object` argument is gated and row-filtered (it was
    invisible to the ACL walk). The ACL and row-policy walks refuse an AST child of a type they
    have no rule for instead of skipping it, and the column-policy rewrite denies a statement in
    which a covered column sits where it cannot be masked (a MAP or list literal, a lambda, `->>`,
    a `JOIN ... USING` list, ...) instead of forwarding it unmasked.
  - Lockdown judges file literals after `SUMMARIZE`, `DESCRIBE`, `TABLE`, the `PIVOT` / `UNPIVOT`
    statements and `USING` (`MERGE INTO .. USING`, `DELETE .. USING`), and protects the iceberg
    `unsafe_enable_version_guessing` setting.
  - A column- or row-policy rewrite refused for holding `//` says so instead of "could not parse
    statement".
  - The column-policy rewrite masks a covered column in `IS [NOT] NULL`, `TRIM`, `QUALIFY`, an
    aggregate `FILTER` and `JOIN ... ON` instead of denying the statement. It also stops denying a
    CTE, a derived table or an `IN` / `EXISTS` / scalar subquery that reads a same-named column
    from a table with no policy (a staging CTE joined to a masked table, common in dbt): a
    covered-column check now binds unqualified names per query block, innermost first, as DuckDB
    does. `JOIN ... USING` on a covered column stays denied.
  - BREAKING for names holding a dot, an `@` or an escaped quote: a double-quoted or
    backtick-quoted identifier containing a dot (`"a.b"`), an `@` (`"secret@x"`) or its own
    doubled delimiter (`"a""b"`) is refused for ACL-checked users (QoD grants and OPA), users with
    column or row policies, and every caller on a lockdown pool. DuckDB reads each as one name,
    while the access check read `"a.b"` as schema `a`, table `b`, `"secret@x"` as table `secret`
    (a database link) and `"a""b"` as `a""b`, so a grant on schema `a` reached `main."a.b"` and a
    grant on `secret` read, wrote and dropped `secret@x`. The rule is lexical, so it also refuses
    such a quoted COLUMN or struct-field name (`"price.usd"`, `s."a.b"`, common in CSV- or
    JSON-derived tables), in any position but directly after `AS`. An alias (`AS "t.x"`) is still
    accepted, and a remote URL starting with a scheme (`"s3://b/x.parquet"`) passes this screen to
    be refused as the file read it is (see below; a `://` anywhere else, `"other.x://"`, is
    refused here); rename such tables and columns (an `AS` alias only renames an output label).
  - `quack_active_connections()` (every session's running query text and connection id) and
    `quack_server_list()` join the node-control calls reserved to superusers and refused to every
    caller on a lockdown pool.
  - The ACL walk scopes a CTE name to the query that declares it: a CTE inside `ARRAY(...)` or a
    derived table is no longer gated as a table of that name.
  - A CTE name no longer hides a read of the real table of that name. Every name of a `WITH`
    clause was in scope before any body was walked, while DuckDB binds a body's reference to its
    own name, or to a later sibling's, to the real table: `WITH orders AS (SELECT * FROM orders)`
    (common in dbt SQL) read the table `orders` with nothing gated, top level, nested and on a
    write's `WITH` clause. The walk now follows DuckDB 2.0's visibility exactly: a body sees the
    CTEs declared before it, the main query all of them, and in `WITH RECURSIVE` a union body's
    recursive term also sees its own name. The column-policy residual check binds CTE names by the
    same rules.
  - `ARRAY(TABLE t)` and the quantified comparison `x = ANY | ALL | SOME (TABLE t)` read every row
    of t; JSqlParser holds t as a column, so the ACL admitted them with nothing gated (also in
    `SET VARIABLE` values and write sources). The table is now gated; the column-policy rewrite
    denies the form and the row-policy rewrite refuses it on a policy table.
  - The column-policy rewrite no longer schema-qualifies a CTE reference. It printed
    `WITH t AS (..) SELECT .. FROM t` as `.. FROM <schema>.t`, a read of the real table `t` the
    ACL never checked (it saw the CTE), for any principal with a mask policy whose statement
    touched a masked column; where no such table existed the statement failed instead. Table names
    now go out exactly as written. As defense in depth, every rewritten statement (column
    policies, row policies, metadata filter) is refused if it needs a grant on a table or verb the
    original did not, and is re-validated for the same principal in the same ACL mode before it
    reaches a node.
  - BREAKING for principals with a column (mask) policy: `COLUMNS(...)` in any position, the
    unpacked `*COLUMNS(...)`, star patterns (`* LIKE` / `ILIKE` / `NOT LIKE` / `GLOB` /
    `SIMILAR TO`, `t.* LIKE`) and any star inside an expression other than `count(*)` are refused
    where a table with a column policy is in scope: each returned masked columns raw. So are a
    table referenced as a column (`SELECT c FROM customer c`, `main.customer`, `to_json(c)`: the
    whole row as a STRUCT) and a statement in table position over such a table (`(TABLE t)`,
    `(SUMMARIZE t)`). Name the columns instead. `* EXCLUDE (..)` and `* REPLACE (.. AS ..)` (over
    one table, or `t.*`) are now expanded to an explicit, masked column list: the rewrite used to
    drop the modifier (excluded columns came back masked, a replacement was lost), and an aliased
    table's star now expands under its alias (`SELECT * FROM customer c` used to fail on the node).
    A modifier that cannot be expanded exactly over a table with a column policy is refused.
  - DuckDB's method-call syntax on a masked column (`c_email.lower()`, `c.c_email.upper()`, in any
    clause) went out unmasked: JSqlParser holds it as a function with a multi-part name and no
    column. It is now rewritten to the function form and masked (`lower('***')`); a field read of
    a masked column (`c_email.x`) is refused.
  - A `NATURAL` join comparing a masked column is refused, like `JOIN ... USING` on one: joined
    against a guess list it recovered every value of an enumerable masked column (phone numbers,
    dates of birth) with its row.
  - A FROM-first statement (`FROM t`) of a principal with a column policy is refused cleanly
    instead of failing with an internal error.
  - A method call on a masked table's binding (`c.to_json()`, `customer.to_json()`,
    `c.list()`, `c.struct_extract('c_email')`, in any clause) is refused like `to_json(c)`: DuckDB
    hands the function the whole row, every masked column raw.
  - BREAKING for principals with a column policy: an alias column list on a table with a column
    policy (`customer AS c(a, b, e)`, with or without `AS`, in a join or a NATURAL join) is
    refused. It renamed a masked column positionally (`SELECT e` read `c_email` raw) under the
    default lenient resolver. Alias each column in the select list instead; a column list on a
    derived table or a CTE stays accepted, its values masked inside it.
  - BREAKING: a table name DuckDB reads as a file is refused like the string literal it stands
    for (`FROM 's3://b/x.parquet'`): whenever the ACL validates, it is refused to every
    tenant-scoped principal, `*.*.* ALL` included (see the file-access entry below), in every
    position (FROM, DESCRIBE / SUMMARIZE, write and DDL targets). DuckDB 2.0 replacement-scans a
    name it cannot resolve, its parts joined with dots, so a quoted URL (`"s3://b/*.parquet"`,
    `` `file:///d/x.csv` ``) or a quoted path (`"/d/x"."csv"`) read the object or file with the
    node's credentials, while the access check saw a table a wildcard read grant (`*.*.* RO`,
    `<db>.*.* RO`) admits: a raw read of the pool's DuckLake data files, past any row policy.
    Refused now: a name with a part holding `/`, `\`, `*`, `?`, `[` or `{` (a URL's `://`
    included). A real table named like one (`"data_*"`) is unreachable for tenant principals;
    rename it. Known gap: an unquoted relative file name (`x.parquet`, schema `x` and table
    `parquet` not found) is still read as a file under a wildcard read grant.
  - BREAKING for quoted names JSqlParser misreads: the quoted-identifier screen no longer lists
    characters but reads each quoted name back through the access check's parser and refuses it
    unless that reading is the name DuckDB reads. Newly refused: square brackets (`"[secret]"`,
    `"secret]"` were checked as `secret` while DuckDB reads the table `[secret]`) and a quote of
    the other style at a name's edge. A re-validated rewrite is screened only for the quoted names
    the caller wrote, so a star expansion over a column named `a@b` is no longer refused; a star
    over a column whose name holds a dot is refused under column policies (the rewrite could only
    print it as another column).
  - The dbt shape `WITH customer AS (SELECT * FROM customer) SELECT c_email FROM customer` is
    masked instead of refused for a principal with a column policy.
  - BREAKING: tenant users can no longer read or write files by path; a superuser or a
    federated source must do it. Whenever the ACL validates (QoD grants or OPA), a tenant-scoped
    principal, `*.*.* ALL` included, is refused file-reading table functions (`read_parquet`,
    `read_csv`, `read_json`, `glob`, `read_text`, `parquet_metadata`, `iceberg_scan`, ...), a
    path or URL in a table position (`FROM 'x.parquet'`, `JOIN 's3://..'`, `FROM "s3://.."`),
    `COPY` to or from a path, `EXPORT` / `IMPORT DATABASE`, `ATTACH`, and `sql_auto_complete`
    (it lists node directories). These were "unsupported constructs" a `*.*.* ALL` grant
    covered, so a tenant user (the built-in `qod_all_tables` role is `*.*.* ALL`) could read
    anything the node's process and secrets reach (another tenant's DuckLake data files, the
    node's disk) and write files with COPY. A statement that parses is judged from the parse (a
    keyword used as an alias, `FROM range(1) AS values, '/etc/x.csv'`, no longer hides the file
    after it; `x IS DISTINCT FROM 'y'` is a value, not a file); one that does not parse is judged
    lexically and conservatively (any string literal after FROM, JOIN, USING, TABLE, a comma or
    such an opening parenthesis is refused). A quoted name part holding a glob character
    (`"*".csv`, `"data_*"`) is refused whatever its position or extension (a conservative
    refusal: DuckDB reads `"data_*"` without an extension as a table name). Superusers, an
    ACL turned off, the native front door's client data (`scan_data_from_quack_client`),
    federated and Iceberg catalogs read by table name are unchanged.
  - BREAKING: tenant users can no longer install extensions or set path-resolution settings, ACL
    on or off. `INSTALL` (any form), `FORCE INSTALL`, `UPDATE EXTENSIONS` and `LOAD` from a path
    or URL are reserved to superusers (install at node start; `LOAD httpfs` by its bare name still
    works), and so are `file_search_path` (`SET file_search_path = '/etc'; SELECT * FROM x.csv`
    read /etc/x.csv), `home_directory`, `allow_asterisks_in_http_paths`, `ca_cert_file` and the
    server certificate verification settings, all GLOBAL on DuckDB 2.0. tpch's `dbgen` (creates
    and fills tables in any catalog at any scale) is reserved to superusers too. Known gap: only
    lockdown turns off `autoinstall_known_extensions`, so off lockdown a function call or a bare
    `LOAD` of a known extension still installs it.
  - `shell_history()` is refused to every caller, superusers included: before this release a
    node's init SQL reached its DuckDB CLI through stdin, so the history held the object-store
    secret keys, the catalog password and the node token, readable by any tenant user with
    `*.*.* ALL`, and by everyone with the ACL off. The init SQL now goes through `-init`, which the
    CLI does not record (see the `shell_history()` entry above); the refusal stays as defense in
    depth.
  - BREAKING: tenant users can no longer read node state, ACL on or off: `duckdb_secrets()`,
    `which_secret()`, `whoami()`, `duckdb_settings()` / `pg_settings` and `current_setting()`
    (except over a session setting such as `TimeZone` or `search_path`), `duckdb_logs*`,
    `duckdb_log_contexts`, `duckdb_external_file_cache`, `duckdb_temporary_files`,
    `duckdb_databases` / `PRAGMA database_list`, `duckdb_extensions` / `PRAGMA
    extension_versions`, `duckdb_extension_repositories`, `duckdb_external_resources` and
    `pragma_storage_info` are reserved to superusers. They showed credentials or their names
    (`key_id`, hosts, users, unredacted `s3_secret_access_key`), other sessions' statements and
    file paths, and the node's own paths. `duckdb_settings()` is refused rather than filtered, its
    values being reachable through `current_setting()` with a computed name.
  - DuckDB 2.0's `histogram()` / `histogram_values()` table macros run `query_table(source)` in
    their body, so `SELECT * FROM histogram('/etc/x.csv', b)` read any file, and
    `histogram('<table>', col)` any table (a row-policy table unfiltered), for a `*.*.* ALL` tenant
    and on a lockdown pool with the ACL off. Every 2.0 macro is now judged by its body: these two
    are refused in a table position (the aggregate `histogram(expr)` in a select list stays
    admitted), and `duckdb_profiling_settings()` (a macro over `duckdb_settings`) is reserved to
    superusers.
  - `pg_get_viewdef(oid)` and `pg_get_constraintdef(oid)` (DuckDB 2.0 macros over
    `duckdb_views()` / `duckdb_constraints()`, outside the metadata filter) printed the SQL of every
    view and every CHECK expression of the node to any principal enumerating oids. They now need the
    unrestricted `*.*.* ALL` grant, like a direct read of those listings (refused under OPA).
  - On a lockdown pool, a file in table position is judged like a tenant's: from the parse when the
    statement parses, conservatively otherwise. A keyword used as an alias no longer hides a local
    file after the next comma (`FROM range(1) AS values, '/etc/x.csv'`), and a relative path
    (`FROM 'x.csv'`) or a quoted glob (`FROM "*".csv`) is denied; remote object-store URLs stay
    admitted outside the DuckLake buckets.
  - An alias column list on a parenthesized FROM item holding a table with a column policy
    (`(customer) AS c(a, b, e)`, `(customer JOIN orders ..) AS j(..)`) is refused like one on
    the table itself: it renamed a masked column out of the policy's reach.

- **Fixed: an unaliased expression over a masked column no longer fails under column policies.**
  For a user with a column mask, a query projecting a masked column through an unaliased
  expression (`SELECT min(c_phone) FROM customer`, `c_phone || 'x'`) failed on FlightSQL with
  `Binder Error: Values list "_qod_probe" does not have a column named "min"`, and so did any
  `SELECT * FROM (<such a query>) s`: the column-policy rewrite expanded a star over a derived
  table under its own name for the unaliased column, which DuckDB does not bind. A star over
  derived tables only is now kept as written; the derived tables are masked inside it.

- **Fixed: native front door transactions survive a client pause.** Client heartbeats now reach an
  open transaction's node connection too, so a statement after an idle pause longer than the
  node lease no longer fails with "Connection heartbeat lease expired".

- **Fixed: a slow-starting local node is no longer respawned into an orphan.** The local runtime
  records a node as soon as its process starts, before it listens. A reconcile pass in that window
  (more likely with a slow init SQL or a short `QOD_RECONCILE_INTERVAL_SEC`) read the node as dead
  and started a second process under the same node id without stopping the first, which kept
  running untracked, held its port and outlived the manager. A node whose process is alive now
  counts as starting for 120 s after its spawn, and replacing a node stops its old process first,
  including one an earlier manager left running. A node's process is recognized by its start time,
  not its pid alone, so a pid reused by another process after a restart is never adopted or
  signalled; such a process's port is kept out of new nodes until it exits, with a warning naming
  the pid. Kubernetes and fleet nodes were not affected: their
  start returns only once the node is ready, and a pod name or fleet assignment is unique per id.

- **Security: a batched `USE` or `SET search_path` no longer reaches tables outside a user's
  grants.** The access check resolves a bare table name against the pool's default schema, but a
  `USE`, `SET search_path`, `SET schema` (any spelling, including `SET SESSION`, `TO`, `PRAGMA
  search_path`, `RESET`, a backtick-quoted name and the `SET` an `EXPLAIN ANALYZE` carries) in the
  same request moved the node elsewhere first, so
  `SET search_path='db.other'; SELECT * FROM t` read `db.other.t` with a grant on the default
  schema's `t` only, over FlightSQL and the native front door alike. For ACL-checked users such a
  batch is now refused: send `USE` / `SET search_path` as its own statement, which works as
  before. Superusers are unaffected. Every multi-statement request now also starts from the pool's
  default schema, even when it opens with `BEGIN` or `SET`; one opening with `ROLLBACK` / `COMMIT`
  switches to it right after that statement, so a rollback out of a failed transaction still runs.

- **Security (DuckDB 1.x builds only): a backtick outside a string or comment is refused for
  ACL-checked users, users with column or row policies, on lockdown pools and on pools with a
  read-only catalog.** On DuckDB 1.5 a backtick is an operator, not an identifier quote, so with a
  macro named after it the text between two backticks runs: statement separators and function
  calls there were hidden from the access checks. The screen is keyed on the DuckDB version the
  build pins and is inert on DuckDB 2.0, where a backtick quotes an identifier and the edge's lexer
  reads it as one. On 2.0 the edge renders a backtick-quoted identifier as the equivalent
  double-quoted one before the access checks and the column and row policy rewriters see the
  statement, so a backtick-quoted table or column is masked and filtered like its double-quoted
  twin.

- **Security: `SET VARIABLE` can no longer read a table without a grant.** Its value is a query,
  so `SET VARIABLE x = (SELECT ... FROM any_table); SELECT getvariable('x')` returned rows from any
  table the node could reach, with no grant at all: the `SET` passed as table-free control flow.
  A `SET` whose value reads a table is now denied unless the user holds the unrestricted `ALL`
  grant; `SET VARIABLE x = 42` still works.

- **Security: a subquery passed straight to a function is access-checked.** DuckDB's
  `ARRAY(SELECT ... FROM t)` hands its subquery to the function without parentheses of its own, and
  the access check skipped such a subquery entirely, so the statement needed no grant on `t` at all.
  A subquery passed as a function argument is now access-checked like any other read; one inside
  a table function's argument is refused as an unsupported construct.

- **`qod admin reset-password` never starts Postgres.** It used to start the `qod serve` embedded
  Postgres on demand whenever that data dir existed and `QOD_PG_HOST` was unset, so a data dir left
  by an earlier `qod serve` captured the default: on a machine running `qod start` against its own
  Postgres, the reset booted the stale embedded cluster, reset the admins there, reported success,
  and the live manager kept the old password. The default target is now the embedded control plane
  only while its Postgres is running, else the external `QOD_PG_*` one; an explicit `--embedded` on
  a stopped one is refused. Recovering an embedded admin password therefore needs `qod serve`
  running first; against your own Postgres it still works with the manager down.

- **A wrong password on `qod login` says so.** The CLI replaced every 401 message with "session
  expired or invalid, run qod login", including the login endpoint's own answer, so `qod login`
  told you to run `qod login`. The credential-checking endpoints (`login`, `change-password`,
  `forgot-password`, `reset-password`) now show the server's message.

- **The boot banner shows the node lockdown state.** A new `NODE LOCKDOWN` line sits under
  `SQL ACL`. Lockdown is off by default, and without it (and with the ACL off) a tenant can ATTACH,
  LOAD and autoinstall extensions, and read local files, including the node's secret files on
  Kubernetes, and the cloud SDK variables (`AWS_*`, `AZURE_*`, `GOOGLE_*`) a node inherits, which
  nothing on screen said. The catalog password and the federation and object-store secrets are no
  longer in the node environment (see the `QOD_SECRETS_DIR` entry above). The line counts unlocked pools, so a per-pool `lockdown: off` under a
  global `QOD_NODE_LOCKDOWN=true` shows up too.

## 0.9.11

- **Security: a custom admin password now holds on demo stacks.** The bundled demo manifests
  (`bootstrap-demo.yaml`, `bootstrap-demo-minimal.yaml`) declared a superuser `admin` with password
  `admin`. Boot seeds `admin` with your first-boot password, then the demo import replaced it, so
  every demo stack (compose or `qod start` with `LOAD_*`, `qod serve --demo`) accepted `admin` /
  `admin` whatever `QOD_ADMIN_PASSWORD` said, while `admin@localhost.local` got your password. The
  entry is gone: both seeded admins now take the password you chose, and `admin` / `admin` stops
  working wherever you set one. Default runs are unchanged (`admin` / `admin`).

- **The demo banner shows the real admin logins.** It hardcoded `admin` / `admin` and left out
  `admin@localhost.local`. It now lists every seeded admin name, with `admin` as the password when
  the demo defaulted it and `<QOD_ADMIN_PASSWORD>` when you exported one, so a chosen password is
  never printed.

- **Docker launchers accept `QOD_ADMIN_USERNAME` / `QOD_ADMIN_PASSWORD`.** `docker-compose.yml` and
  `scripts/docker/run-docker.sh` only read the short `ADMIN_USERNAME` / `ADMIN_PASSWORD`, so an
  exported `QOD_ADMIN_PASSWORD` was silently ignored. Both names work now and the `QOD_` one wins
  (the `.env` created from `.env.example` always sets the short one). The default stays `admin`, and
  like everywhere else it applies on the first boot only: rotate later with
  `qod auth change-password`.

- **`run-docker-compose.sh` honours remapped ports.** With `MANAGER_PORT` / `EDGE_PORT` set, the
  readiness probe still polled `localhost:20900` (so a manager already running there made the wait
  pass without checking the stack) and the banner printed `:20900` / `:31338`. Both use the
  configured ports now.

- **The CLI config file moves to `~/.qod/config.toml` on every platform**
  (`%USERPROFILE%\.qod\config.toml` on Windows), from the platform config dir
  (`~/Library/Application Support/qod` on macOS, `~/.config/qod` on Linux, `%APPDATA%\qod` on
  Windows). A file at the old location is moved on first use, keeping mode 0600; when both exist the
  new one wins and the old one is left alone. `QOD_CONFIG_FILE` still overrides the path. Data and
  cache dirs do not move.

- **`scripts/` is grouped by audience** (checkout users only). Entry points stay at the top level
  (`run-jar`, `stop-jar`, `spawn-quack-node`, the demo loaders, `install.sh`,
  `kill-quack-nodes.sh`); the Docker launchers move to `scripts/docker/`, release tooling to
  `scripts/release/`, `adbc.sh` and the load tests to `scripts/bench/`, developer shortcuts to
  `scripts/dev/` and the Iceberg fixture to `scripts/fixtures/`. Update any path you scripted, for
  example `./scripts/docker/run-docker-compose.sh`. The Iceberg fixture's compose project name
  changes from `scripts` to `fixtures`, so bring a running fixture down with the old path first.
  `scripts/README.md` describes every script.

- **PAT revoke: a failed statement kill or kill broadcast is now visible.** A committed revoke
  still answers 200 and writes its audit event (the tokens are already dead; a still-running
  statement stays bounded by its own timeout), but a kill or broadcast that throws is now
  WARN-logged instead of silently swallowed, and the `AuthPatRevoke` audit event's detail gains
  `killFailed` and `broadcastFailed` (`true` / `false`) so `killedStatements: 0` is
  distinguishable from nothing-to-kill. Refs #86 (items 2 and 4).

## 0.9.10

- **BREAKING: the user account field `role` is now `kind` on every surface.** The
  `qodstate_user.role` column (admin | user, the management-rights flag, never an RBAC role) is
  renamed `kind` by Liquibase `0045`. REST `user/create`, `user/update` and `user/list` carry
  `kind`, and a request still sending `role` is refused with 400 naming the new field instead of
  being silently read as `kind=user`. The CLI takes `--kind`, and `qod user create --role` now
  names an RBAC role to attach. MCP takes `kind`. A manifest user entry uses the key `kind`, and
  the old key is refused. The SQL dialect is unchanged (`ADMIN`). The boot config `admin.role` /
  `QOD_ADMIN_ROLE` is renamed `admin.kind` / `QOD_ADMIN_KIND`; the old env var is now ignored. A
  custom `QOD_AUTH_DB_SYSTEM_QUERY` or `QOD_AUTH_DB_TENANT_QUERY` that selects `role` from
  `qodstate_user` must select `kind`, or boot fails. The session JWT `role` claim and the login
  and whoami responses are unchanged.

- **Upgrade: stop every manager replica, then start them all on this version.** There is no rolling
  upgrade across this release: once Liquibase `0045` renames the column, a replica still on the
  previous version can no longer authenticate anyone, because its auth queries select `role`.

- **Every tenant now carries four protected built-in roles and groups.** The role `qod_all_tables`
  holds one permission, `ALL` on `*.*.*`; the role `qod_no_tables` holds nothing; the group
  `qod_all_pools` holds one tenant-wide pool grant (every pool of the tenant); the group
  `qod_no_pools` holds nothing. Their definition (delete, permissions, column and row policies,
  role bindings, pool grants) is refused with 409 `builtin_protected`, while adding and removing
  users stays allowed. The `qod_` name prefix is reserved for them, case-insensitively: creating a
  role or group with it answers 400 `reserved_name`. They are seeded on tenant create, on manifest
  import, and by a boot backfill for existing tenants, which renames any user-made row already
  holding a built-in name to `<name>_renamed` and folds a pristine legacy `admin` role (exactly
  `ALL` on `*.*.*`, no policies) into `qod_all_tables`, moving its users and groups over. New
  tenants no longer get an `admin` role.

- **Roles and groups are chosen when a user is created.** REST, CLI (`--role` / `--group`,
  repeatable), MCP, the SQL dialect (`CREATE USER ... ROLES r1, r2 GROUPS g1`) and the admin UI
  take the lists of role and group names. An omitted list defaults to `qod_all_tables` and
  `qod_all_pools`, which gives the new user full data access to every table and every pool of the
  tenant; pass narrower lists (for example `qod_no_tables` / `qod_no_pools`) to start from
  nothing. An explicitly empty list is refused (400 `roles_required` / `groups_required`), as is an
  unknown name. Superusers take no lists, and SCIM provisioning and manifest import attach no
  defaults.

- **Manifest export refuses while any tenant holds a user-made `qod_` role or group.** Such a row
  (left from before the prefix was reserved, or renamed `<name>_renamed` by the boot backfill,
  which keeps the prefix) cannot be replayed by an import, so the whole export answers 400
  `reserved_name` and lists the offending rows instead of producing a manifest that fails later.
  Rename those rows by hand, then export again.

- **Tests run from a git worktree.** A forked `sbt test` in a worktree now finds libduckdb through
  the main checkout's `.duckdb` cache, and a native library that cannot load fails the statement
  with an error instead of hanging the suite.

- **Per-tenant OPA authorization.** `qod tenant set-acl` (or `POST /api/tenant/setAcl`) hands a
  tenant's data-access decisions to its own OPA server: pool access at handshake and table access
  per statement, with QoD grants ignored for that tenant. It fails closed (only a literal
  `allow == true` admits; an outage or timeout is `UNAVAILABLE`, never a permission error), and
  superusers never reach OPA. Configured by `QOD_ACL_MODE`, `QOD_OPA_URL`, `QOD_OPA_TIMEOUT_MS` and
  `QOD_OPA_CACHE_TTL_SEC`; `qod tenant opa-test` is a dry run. A starter policy is in
  `examples/opa/`.

- **The admin password is set once, at first boot, and never stored.** Admin rows are seeded only
  when missing and never rewritten, and there is no built-in default: with `QOD_ADMIN_PASSWORD`
  unset a missing admin is not seeded. `qod start` / `qod serve` prompt for it on first boot (or
  take it from the shell) and refuse without either. Before, it sat in plaintext in `config.toml`
  and a restart re-hashed it, silently undoing `qod auth change-password`. Recover a lost one with
  `qod admin reset-password`, which works with the manager down.

- **`qod start` / `qod serve --with-starflow` run Starflow next to the manager**, installed on first
  use, its metadata in a `starlake` database on the manager's Postgres, paired for SSO, REST and
  FlightSQL. The compose twin is the `starflow` profile (`STARFLOW_ENABLED=true`). Startup banners
  now link to the client connection docs instead of printing connection strings.

- **Fleet servers can run as Docker containers.** The `starlakeai/quack-on-demand-worker` image runs
  `qod fleet join` with DuckDB, capacity follows cgroup limits, `QOD_FLEET_NODE_PG_HOST` /
  `QOD_FLEET_NODE_PG_PORT` rewrite the Postgres address in assignments, and the
  `docker-compose.fleet.yml` override runs the bundled stack in fleet mode with two workers.

## 0.9.9

- **Security: a personal access token on the catalog endpoints no longer runs as superuser.**
  The guard admits a PAT on `/api` wherever its owner's session would be admitted, but the catalog
  preview, data diff, undrop and restore handlers resolved their executor identity through the
  session-only lookup, and a credential it could not resolve ran as the synthetic superuser. A
  tenant-admin PAT therefore read any table of its tenant's databases with no per-table ACL, no
  column masking and no row filtering, and undrop and restore wrote as superuser; the PAT's own
  restriction (pools, maxRows, branchOnly) was dropped too. The tenant boundary held. The same
  applied over MCP: an agent's `restore_snapshot` and `undrop_table` curry its PAT into these
  handlers, so they ran as superuser; they now run as the PAT's owner and can answer `acl_denied`
  where they used to succeed. These handlers now resolve the caller in one place (`RestCaller`):
  the static key runs as the system caller, a session as its user, a PAT as its owner with its
  restriction and id (its maxRows also caps the preview and diff page), and any other token is
  refused with 401 before a statement runs. The restore dry run and the branch change counter
  still run as the system caller, and return aggregate change counts only.
  The branch endpoints (create, changes, propose, merge, discard) had the same fallback: a token
  that resolved as neither a session nor a PAT (a session expiring between the guard and the
  handler) acted as the superuser admin, able to approve a merge. It is now refused with 401
  before the branch service runs. A PAT created with `--branch-only` can no longer merge a branch
  (merging writes main): 403 `branch_only_token`.

- **Security: a user named "superuser" no longer bypasses the data-plane checks.** The routed
  executor behind the catalog endpoints and the MCP data tools granted the synthetic superuser
  set (no ACL, no column masking, no row filtering, no protected-write guard) to any caller whose
  user name was `superuser`, and user names are not reserved: a tenant admin could create such a
  tenant user and use its session or PAT. Privilege now comes from a typed system flag set only
  for the static key and the two internal system legs above, never from a user name.

- **The native client finds libduckdb without relying on a build-machine path.** libquackwire
  links libduckdb dynamically, and the only library path baked into a vendored binary is the cache
  directory of the machine that built it. `run-jar.sh` and `qod start` / `qod serve` cover this by
  putting their cache on the loader path, but a JVM started through a bash script on macOS never
  sees `DYLD_LIBRARY_PATH` (System Integrity Protection strips it), so `sbt test` and similar
  launches failed to load libquackwire. The manager now loads the pinned libduckdb first, from
  `$DUCKDB_CACHE_DIR/<version>/lib` or `./.duckdb/<version>/lib`, which the loader then reuses for
  libquackwire; the version is read from a stamp now bundled next to the natives. Finding nothing
  falls back to the loader path as before.

- **BREAKING: `qod agent` is now `qod fleet join`, with no alias.** The fleet server process moved
  under `qod fleet`; its flags and `QOD_FLEET_JOIN_TOKEN` are unchanged, and so is its default state
  directory, so an upgraded server still reaps a node orphaned by the previous version. **Before
  upgrading `qod` on a fleet server, change its unit file or launcher from `qod agent ...` to
  `qod fleet join ...`**: 0.9.7 and 0.9.8 shipped `qod agent`, which now fails with "No such
  command". The version a server reports is now `qodVersion` everywhere: in the heartbeat body, in
  `GET /api/fleet/servers` and the admin UI (was `agentVersion`), and in the
  `qodstate_fleet_heartbeat.qod_version` column (renamed by Liquibase `0043`). A server still on
  0.9.7 or 0.9.8 keeps heartbeating (its `agentVersion` is ignored, not rejected) and is simply
  listed without a version until it is upgraded.

- **DuckDB upgraded 1.5.5 -> 1.5.6** across every pinned layer: the DuckDB JDBC driver
  (`1.5.6.0`), libquackwire rebuilt against libduckdb 1.5.6 (`1.5.6-7e80f7ffcc98-1`; the
  duckdb-quack pin is unchanged, DuckDB 1.5.6 ships the same `7e80f7f` quack extension), and
  the runtime DuckDB CLI fallbacks (`run-jar.sh`, `run-jar.ps1`, `qod start --demo`, the K8s
  node image). The node wire protocol is unchanged. DuckLake picks up the 1.5-line backports
  (inline flush with hive partitioning, stale-schema invalidation, a Postgres metadata query
  fix).

- **Iceberg sources: AWS credentials for Glue, S3 Tables and sigv4.** These catalogs sign with
  AWS SigV4, but the typed source had nowhere to put AWS credentials: no secret was rendered, so
  DuckDB either failed the attach (`Could not find a valid storage secret`, `... does not have a
  region`) or signed with the node's default s3 secret, usually QoD's own storage credentials. A
  typed `iceberg_rest` source now takes `awsCredentials` (`config` with `awsKeyId` +
  `awsSecretAccessKey` [+ `awsSessionToken`], or `credential_chain`), `awsRegion` (required for
  glue and sigv4, read off the ARN for s3_tables) and `awsScope`, rendered as a `TYPE s3` secret
  named on the ATTACH. The scope is mandatory: DuckDB breaks ties between equally matching S3
  secrets by name, so an unscoped `qod_ice_<alias>` would outrank the node's own `quack_s3` on
  every S3 read. Available over REST, `qod federation create --aws-*`, the MCP admin tool and the
  admin UI, which now also explains what Warehouse means for glue (account id) and s3_tables
  (table bucket ARN). A stored source without AWS fields renders unchanged.

- **Read-only management views for external Iceberg catalogs.** An attached `iceberg-rest`
  federated source can now be browsed the same way as the tenant-db's own DuckLake catalog:
  schema and table listing, table detail (columns and data/delete files), snapshot history,
  preview at a past snapshot, and a row-level diff between two snapshots. New REST routes under
  `/api/catalog/tenant/{tenant}/database/{db}/iceberg/{alias}/...`, `qod catalog ... --iceberg
  ALIAS`, and an optional `iceberg` argument on the MCP `table_history` and `describe_table`
  tools. Admin-only (tenant admins and superusers); preview and diff run as the calling identity,
  so ACL, CLS and RLS apply exactly as on a normal query. Snapshot ids are strings everywhere on
  the wire, since Iceberg ids are random 64-bit values too large for a JSON number to round-trip
  safely. Table detail is current-snapshot only; time travel lives in preview (`--as-of` /
  `--as-of-ts`, no `--as-of-tag`) and diff. New setting `QOD_CATALOG_ICEBERG_DIFF_MAX_FILES`
  (default 200) refuses a diff (413) once either snapshot has more data files than that, since the
  diff scans both versions in full. Iceberg format v1 tables are refused everywhere in these views
  (400), because v1 carries no per-snapshot sequence number for history ordering and paging to use.
  DuckLake-only operations (restore, undrop, tags, schema diff, catalog-wide snapshot list) are not
  exposed on an Iceberg source; DuckDB's Iceberg extension does not support them.

- **Admin UI: the catalog is one click away, Iceberg included.** The top menu has a Catalog link,
  and the catalog shown inside a database (Tenants > tenant > Databases > table count) now lists
  that database's external Iceberg catalogs too; an Iceberg namespace is picked from a dropdown.
  The selected schema or namespace is readable in dark mode.

## 0.9.8

- **Security: a session expiring mid-request no longer gains superuser access (#130).** Handler
  gates re-resolved the caller's token and read "no scope" as "static `QOD_API_KEY`, unrestricted",
  which is also what a session that expired or was revoked after the API guard admitted it looked
  like. A tenant admin could reach the superuser-only manifest export, see every tenant in the
  tenant, history, usage and audit listings, and pass the tenant-scope gates on RBAC mutations. The
  lookup handed to handlers (REST, MCP, federated sources and the module SPI) now answers "no scope"
  for the configured static key only; any other token that does not resolve gets no privilege. The
  guard now resolves the credential once, so an expiry between its admin check and its tenant check
  can no longer admit a request as admin while skipping the tenant check. Module SPI note:
  `ManagerContext.scopeOf` now resolves PATs and never returns `None` for anything but the static
  key; modules that treat `None` as unrestricted become correct without changes.

- **Security: `query()`, `query_table()` and `ducklake_*` table functions are denied to
  tenant-scoped principals (#128).** These functions name their target in a string the ACL parser
  never sees. Under the tenant `*.*.* ALL` wildcard they read a sibling tenant's catalog that the
  same wildcard refuses when named directly (unparseable statements took the same path), and
  `ducklake_*` calls could read or maintain any catalog by name. For a principal with row or column
  policies they also bypassed RLS and CLS, whether or not `acl.enabled` is on. They are now refused
  for every tenant-scoped principal, lexically, so an unparseable statement cannot slip through.
  Superusers are unaffected. Tenant users lose `ducklake_*` calls on their own catalog too.

- **Branch merge: a main write landing mid-merge is no longer silently lost (#129).** A merge
  validated its change set against main snapshot S and then committed with nothing tying the commit
  to S. DuckLake pins a transaction's snapshot at its first read, not at `BEGIN`, and its commit
  check misses a concurrent insert, update, delete or `ALTER` on a table the merge drops, so a
  racing main write was overwritten or dropped. A trigger on `ducklake_snapshot_changes` in the
  parent catalog database (schema `qod_merge_fence`) now refuses the merge commit unless it lands
  directly on S; the merge answers `409 concurrent_write` and can be re-proposed. Any main commit in
  that window refuses the merge, including writes to unrelated tables.

- **Fleet: joining servers must be approved (#125).** A fleet server takes nodes, and with them the
  metastore credentials, only once approved; the join token alone is no longer enough.
  `QOD_FLEET_AUTO_APPROVE` (CIDRs) approves servers whose heartbeat comes from inside the list; the
  default `0.0.0.0/0,::/0` keeps existing fleets working and logs a warning at boot, and an empty
  list approves nobody automatically. `QOD_FLEET_TRUSTED_PROXIES` names the proxies whose
  `X-Forwarded-For` is trusted. Admins approve with `qod fleet approve <name>`,
  `POST /api/fleet/server/approve` or the Approve button on the Servers page. An approval is bound
  to the source address it was granted to: a heartbeat from another address is refused
  (`409 source_change_refused`) unless that address is in the list or the server is drained with no
  node. Migrations 0041 and 0042 run on upgrade; existing servers are backfilled as approved.

- **Fleet: reconcile repairs a node row that still names the previous server.** A manager that died
  between a fleet claim and the node-row write left the row pointing at the old server, so health
  probes and queries went to a dead address while the real node sat idle. Reconcile now takes the
  address and token from the server holding the assignment and rewrites the row.

- **`qod` names its profile file, and `qod agent` suggests fixes.** Every command prints the profile
  in use and the config file it read, on stderr so `--json` output stays clean. A failed agent
  heartbeat prints a one-line likely cause and fix (https against the plain-HTTP port, DNS, refused
  connection, timeout, untrusted certificate, wrong join token, pending approval and each approval
  conflict), once per distinct failure.

## 0.9.7

- **Fleet runtime: run nodes on bare servers, no Kubernetes (#123).** `QOD_RUNTIME_TYPE=fleet` plus
  `QOD_FLEET_JOIN_TOKEN`. Linux and macOS servers join by running `qod agent`, which heartbeats the
  manager (`POST /api/fleet/heartbeat`, header `X-Fleet-Token`) and runs the one node its reply
  assigns through the bundled spawn script, bound to `QOD_NODE_BIND`. The manager claims a free
  server whose reported memory fits the node, waits for the agent to report it running, and tracks
  liveness on the database clock so HA replicas agree. With no free server the slot stays pending
  and reconcile fills it as soon as a server joins. A server silent past `heartbeatTimeoutSec` is
  unroutable but kept; past `reassignAfterSec` its node is respawned elsewhere in one store
  transaction, and when no other server qualifies the dead holder keeps its assignment, so a server
  that comes back resumes its node with no restart. A partial or cancelled spawn rolls back the
  nodes it started. A known server name reporting a new address is refused unless drained.
  `QOD_FLEET_EPHEMERAL=local` runs maintenance and merge nodes on the manager host. Operators get
  `qod fleet servers | drain | undrain | remove` and a Servers page in the admin UI with pending and
  server badges on pools. HA accepts the fleet runtime. Manager-to-node traffic is plain HTTP, so
  fleet mode needs a private network.

- **Native Quack front door: statement, session and kill lifecycle fixes (#121).** Twelve findings
  from an external audit, each pinned by a regression test. `BEGIN` closed its own transaction link,
  so every later statement of a client transaction reused a dead connection. The idle sweeper read
  the clock and session table once at listener start and never again. A gen-1 `APPEND` whose `USE`
  prelude failed escaped as a `MatchError`. Admin results closed their link before the client could
  fetch them. An admin kill of a statement inside a client transaction was a no-op; it now
  disconnects the transaction's link and fences the session until a `ROLLBACK` succeeds, so no later
  statement can silently auto-commit on a fresh connection. Node load accounting leaked a phantom
  in-flight request when a client dropped mid-call. The router's session registry was never closed
  on either edge; the front door and the Flight edge now close and sweep it. The RBAC effective-set
  cache keyed JWT claims by `Set.hashCode`, which collides (`"Aa"` / `"BB"`); the key now carries the
  claim sets themselves.

- **`qod agent` says when it reaches the manager.** It printed nothing on success, so a healthy
  agent looked identical to one silently waiting. It now logs `connected to manager <url> as server
  '<name>'` on its first successful heartbeat and `reconnected ...` on the first success after any
  failed one (network error or non-2xx reply), once per transition.

- **Fleet: see which server runs each node and each statement.** New `qod node list`, one row per
  node with its `server` and `serverState` (`--tenant` / `--pool` filters, `--json`). Statement
  history records now carry `serverName`, stamped from the node id when the statement is recorded,
  returned by `/api/node/statements` and `/api/profile/statements` (so `qod node statements` shows
  it) and shown as a Server column on the admin UI's Recent statements table when any statement ran
  on a fleet server. Empty on the local and Kubernetes runtimes. The persisted statement search
  (History page) does not carry it yet.

- **A restarted manager keeps the nodes that are still running.** The control-plane store read node
  rows back keyed by the tenant's display name, while pools are keyed by the tenant id, so after a
  restart every node of a tenant whose display name differs from its id (`acme` / `Acme
  Corporation`) was dropped from the in-memory pool. The row stayed and reconcile adopted the node,
  but never wrote it back, so the pool showed no nodes and routed nothing. On the fleet runtime the
  agents looked as if they never reconnected, even after restarting them; adopted Kubernetes pods
  were hit the same way. Node rows are now keyed by tenant id.

- **A manifest can reference an existing tenant by its id.** Import validation knew database
  tenants only by display name, so a partial manifest (users, roles or groups without their tenant)
  naming `tenant: acme` was refused with `tenant 'acme' not in YAML or DB` whenever the display name
  differed. The exporter writes ids, so its own output could fail to re-import this way. Both forms
  are now accepted.

- **The FlightSQL and Quack `tenant` parameter is the tenant id, nothing else.** The handshake
  swapped a value shaped like a legacy surrogate id (`t-<hex>`) for the tenant's display name
  before looking up the pool, which could only miss since pools are keyed by id. The branch and
  `Names.looksLikeTenantId` are gone.

- **`QOD_HIBERNATE_SWEEP_SEC` and `QOD_HIBERNATE_IDLE_MIN` are honoured again.** The
  `quack-on-demand.hibernation` block had no camelCase pureconfig hint, so the derived reader looked
  for `sweep-seconds` / `default-idle-minutes`, found neither, and kept the defaults (300 s sweep,
  no manager-wide idle minutes) whatever the environment said; only the single-word `enabled` key
  was read. `HibernationConfigSpec` loads a camelCase overlay so a missing hint fails the build
  instead of the operator's configuration.

## 0.9.6

- **`ATTACH ... (TYPE quack)` now works for users holding column policies, and their masks hold on
  the pushed-down scans (#114, second report).** Two gaps behind one symptom. First, the column-level
  security rewriter runs the attach-time catalog sync (`duckdb_tables() UNION ALL duckdb_views()`)
  through its column resolver, which cannot model a table function and reported a parse failure,
  denied fail-closed: `column policy rewrite could not parse statement`. A statement that reads no
  physical table, only the four filterable catalog functions, now passes that rewriter (decided on
  the ACL parser's complete walk, so a policy-bearing table hidden in any subquery still reaches the
  resolver; batches and other table functions keep the fail-closed path). Second, and worse once the
  first was fixed: the quack client pushes every scan down as `SELECT #1, #2 FROM <table>`, DuckDB
  positional references with no column names, so the resolver saw nothing to mask and forwarded the
  scan unmasked. Positional references are now resolved against the table's physical column order
  before the mask runs, for the one shape whose numbering is unambiguous (a single-table SELECT with
  no join, subquery or set operation, `#n` in the projection, WHERE or an expression); a `#n` as an
  ORDER BY, GROUP BY or HAVING term, in a join or derived table, out of range, or on a table the
  catalog does not know is denied rather than guessed. `QuackCompatibilitySpec` attaches with a
  column mask and a row policy in force and asserts the masked value on a pushed-down scan.

- **Bare references to DuckDB's system views no longer pass a schema-wide grant.** DuckDB keeps
  default views in the `system` catalog's `main` and `pg_catalog` schemas, both on the unqualified
  search path, so `FROM sqlite_master` (every catalog's DDL), `FROM pg_class` / `FROM pg_attribute`
  (every catalog's relation and column names), `FROM duckdb_databases` (every attached catalog and
  its path) and the rest resolve on a node whenever no table of that name shadows them. The ACL
  parser qualified such a name as `<session>.<schema>.<name>` and grant-checked that, so a
  `acme.*.* RO` grant admitted an unfiltered read of the system view. All 36 default views of DuckDB
  1.5.5, plus any `duckdb_` / `pragma_` / `sqlite_` prefixed bare name, are now marked unsupported
  (denied without a wildcard ALL grant, flag on or off), completing the `duckdb_tables` case of
  0.9.5. A three-part name under a real catalog, and an explicit `pg_catalog.X` (the documented,
  schema-grant-gated surface), are unchanged. `DuckDbSystemViewsSpec` pins the list against the
  `duckdb` CLI so a DuckDB bump that adds a view fails the build.

## 0.9.5

- **Native `ATTACH ... (TYPE quack)` now works for ordinary users (#114).** The DuckDB quack
  client syncs the remote catalog on attach with `duckdb_tables() UNION ALL duckdb_views()`, and the
  ACL gate denied that query for every principal without a `*.*.*` ALL grant (`unsupported
  constructs (deny, fail-closed): table function duckdb_tables`), so only superusers could attach.
  The filtered-metadata rewriter now covers DuckDB's four catalog functions (`duckdb_tables()`,
  `duckdb_views()`, `duckdb_schemas()`, `duckdb_columns()`) the way it covers `information_schema`:
  an unqualified, argument-free call in a read-only statement is narrowed to the session catalog
  (`database_name = '<session>'`) and the principal's Read-covering grants, under the same
  `QOD_ACL_FILTERED_METADATA` flag. An attached catalog therefore lists only the tables the user is
  granted; an ungranted table is absent on the client rather than described. Fail-closed as before:
  calls with arguments, qualified calls (`main.duckdb_tables()`), `ROWS FROM`, positions the
  rewriter cannot reach, and a catalog function riding inside a write or DDL statement are denied.
  Also closed while here: the bare spelling `FROM duckdb_tables` (no parentheses), which DuckDB
  resolves to the same function, used to be qualified as an ordinary table and admitted under any
  schema-wide grant, exposing the DDL of every catalog on the node. The ACL parser now marks the
  unqualified, `main.` and `system.main.` spellings unsupported (denied without wildcard ALL, flag on
  or off). `QuackCompatibilitySpec` drives the real DuckDB CLI through the real
  `PostgresAclValidator` with narrow grants instead of a stand-in that admitted everything, and
  `QuackClientSyncFunctionsSpec` pins the vendored client's sync queries to the filterable set, so a
  client bump that syncs through an unlisted function fails the build instead of re-opening #114.

## 0.9.4

- **External Iceberg REST catalogs as a typed federated source.** A tenant-db can now attach an
  Iceberg REST catalog (`qod federation create TENANT DB --alias icelake --type iceberg-rest --uri
  ... --warehouse ...`, `POST /api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources` with a
  typed `config`, the admin console's federation section, or `sourceType: iceberg_rest` in a
  control-plane manifest) instead of hand-written `setupSql`. QoD renders the `ATTACH` itself, so
  the catalog is governed like any other: ACL grants reference it as the first segment of a
  three-part table ref, and every statement goes through the usual routing pipeline. A new `iceberg_rest` source defaults to **read-only**, which
  is enforced twice: `READ_ONLY` on the rendered `ATTACH` (engine-level, from the next node spawn)
  and a new edge screen that refuses a write it can resolve against a read-only catalog before it
  reaches a node. Credentials stay in the federation secret store, never in the rendered SQL a
  `kubectl describe` or an error message can echo. A catalog that is unreachable at node spawn no
  longer disappears silently: the node still comes up healthy serving its other catalogs, the real
  DuckDB error is reported on the node and on the source, and the next health probe re-attaches it
  without a restart.

- **BREAKING: every federated source alias must now be a plain lowercase identifier.** The rule
  that already applied to tenant, tenant-db and pool names (ASCII letters, digits and underscore,
  starting with a letter or underscore, 1..63 chars, stored lowercase) now applies to federated
  aliases too -- **`sql` sources included, not only the new `iceberg_rest` ones**. DuckDB treats
  catalog and secret names case-insensitively, so `Sales` and `sales` were two rows whose second
  `ATTACH` failed at node spawn, and a hyphenated alias needed hand-quoting to attach at all.
  What changes for an existing install:
  - Creating a source with a hyphen, a dot, or an over-63-char alias (`--alias ext-s3`) now
    returns `400` where it previously worked. Pick an identifier alias (`ext_s3`).
  - A mixed-case alias is rewritten to lowercase in place on its next upsert. `Sales_Lake` and
    `sales_lake` resolve to one row rather than minting a second.
  - A source **already stored** under an alias the rule rejects stays editable: an update naming
    that alias keeps the stored spelling and applies the edit, through REST, CLI, MCP and manifest
    import alike. Only its NAME is frozen -- renaming it to a valid alias still means delete and
    recreate, and because the alias is the catalog segment of every role permission, that also
    means re-granting.
  - The manager reports every stored alias the rule would reject or rewrite at boot, at ERROR, so
    an upgrade tells you which rows are affected before you go looking.

- **Encryption at rest for tenant databases.** `qod database create --encrypted` (REST
  `"encrypted": true`, the same switch on the admin console's create form, `encrypted` in a
  control-plane manifest) makes a `kind=ducklake` database write encrypted Parquet, with DuckLake
  minting a key per file into its own catalog, and a `kind=duckdb-file` database an AES-256-GCM
  encrypted file whose key QoD mints per database (or you supply with `--encryption-key`). The key
  is never readable back through any API, so an exported manifest cannot recreate an encrypted
  `duckdb-file` database. `kind=memory` is refused. Encryption is create-time only in both
  directions for both kinds, since neither engine can encrypt or decrypt a database in place: there
  is no `encrypted` field on `database/update`, a manifest import that flips it is refused, and a
  pre-attach guard turns a control-plane row that disagrees with the catalog's recorded
  `ducklake_metadata.encrypted` into one clear message instead of a DuckDB error repeated once per
  node spawn. A branch inherits its parent's encryption. `QOD_REQUIRE_ENCRYPTION=true` (default
  off) refuses any create that does not ask for encryption, gating creates only so existing
  databases keep working. Encrypted nodes load `httpfs`, because DuckDB needs OpenSSL for a
  writable encrypted file. **Kubernetes upgrade note:** node credentials (`pgPassword`, and
  `encryptionKey` for encrypted `duckdb-file` databases) now reach a pod through a per-pool Secret
  instead of the pod's plain environment, and pods created by an earlier manager are not migrated
  in place, so restart every node after upgrading. The trust boundary is stated in the README:
  whoever can read the control-plane Postgres can decrypt the data, for both kinds.

- **Native Quack protocol front door (Epic 5).** Any DuckDB that carries the `quack` extension
  (the CLI, the Python package, an embedded DuckDB) can now `ATTACH 'quack:<manager>:9494' AS qod
  (TYPE quack, TOKEN 'tenant=<t>&pool=<p>&user=<u>&password=<pw>')` or call
  `quack_query('quack:<manager>:9494', ...)` against the manager directly, with no driver in
  between, and join the attached catalog with its local tables. The token string is the same
  set of parameters a FlightSQL JDBC URL takes after `?` (`tenant`, `pool`, `user`, `password`,
  or `token=<OIDC JWT>`, plus `superuser=true` for the system realm). Identity goes through the
  Flight edge's own handshake (extracted into a shared `EdgeHandshake`), and every statement runs
  through the same routing pipeline as FlightSQL (`FlightSqlRouter.executeWith`, the pipeline
  generalized over the node call): ACL, column and row policies, metadata filtering, lockdown,
  author stamping, retry, statement history, audit (source `quack`), kill and scale-to-zero
  wake-up all apply unchanged. The manager relays per statement: it parses only the Quack header
  and the small request messages, opens one raw node connection per statement with the client's
  own protocol hello, and forwards the node's result bytes to the client untouched, so it never
  encodes a DuckDB chunk and has no protocol version of its own (both the shipped generation 1
  extension and the current upstream generation 3 wire are accepted). A generation 1 `INSERT`
  into an attached table (shipped as an APPEND) is authorized as a write on the named table
  before any byte reaches a node, and admin-dialect statements are answered by the manager and
  rendered through a node. New `quack-native` config block (`QOD_QUACK_ENABLED`, `_HOST`,
  `_PORT` default 9494, `_TLS_ENABLED` default false because the DuckDB client only speaks plain
  HTTP to loopback hosts, `_TLS_CERT_CHAIN`, `_TLS_PRIVATE_KEY`, `_MAX_HEARTBEAT_SEC`,
  `_MAX_BODY_BYTES`); the port is exposed in the Dockerfile, docker-compose and the helm chart
  (`service.quack`, `quack.enabled`, `quack.tls.enabled`); the boot banner, `qod serve`'s connect
  box and the pool page's connection card print the `ATTACH` string. Sessions are per replica
  like FlightSQL sessions, so an HA deployment needs a session-sticky balancer in front of the
  port. Known upstream client limitation (extension `40de7ba`, DuckDB 1.5.4): multi-column
  results larger than the inline batch (about 24k rows) fail inside the client with
  `Attempted to access index 1 within vector of size 1`, against a raw node as well; single-column
  results of any size and multi-column results that fit inline work. Covered by an end-to-end
  spec that drives the real DuckDB CLI and the `uv`-provisioned Python client through the front
  door to a real `quack_serve` node. Design note:
  `docs/superpowers/specs/2026-09-20-native-quack-front-door-design.md`.

- **FlightSQL edge no longer mis-surfaces two DDL/DML edge cases as opaque or drifted errors.**
  DuckDB (1.5.x) does not stream every non-result statement as the single-row `Count BIGINT`
  the edge advertises: BEGIN, COMMIT, DROP and ALTER stream a single-row `Success: BOOLEAN`
  instead (a split that does not track the classifier's Read/Write/Ddl buckets - CREATE INDEX
  streams Count but DROP INDEX streams Success; VACUUM/ANALYZE/SET share the DuckDB-level split
  but are always probed and advertised correctly, so they never drift). This tripped ADBC's
  advertised-vs-actual schema check (`FlightSQL endpoint returned inconsistent schema`) and, on
  a stamped ducklake write whose result had already streamed, could crash the DoGet stream
  entirely after the first batch. Both are now handled at the one place literal and prepared
  statements funnel through: a `Success: BOOLEAN` result (any row count - DuckDB can report zero
  materialized rows for the shape) is coerced to the advertised `Count: int64 = 0` before
  streaming, with a WARN if that ever discards real row data (believed impossible for DML/DDL).
  Separately, a stamped write's COMMIT epilogue losing a genuine DuckLake write-write conflict
  (the same "contains conflict" marker `CatalogRestoreHandlers.isConflict` already relies on) is
  now classified as a retryable `UNAVAILABLE` ("concurrent write conflict committing the
  transaction; retry the statement") instead of an opaque internal error - but only for that
  known-transient signature: any other commit-time failure (disk full, a corrupted catalog,
  revoked write credentials) still fails closed to the errorId'd internal error, now always
  logged at ERROR with its own correlation id so an operator sees it even at the manager's
  default quiet log level. Neither statement kind ever carried a meaningful row count and the
  underlying DuckLake write race itself is not retried by the manager. Fixes #106.
- **HA: PAT kill broadcasts are chunked.** Revoking a token cascades the kill of its running
  statements to the other replicas over the `qod_pat_kill` NOTIFY channel. Postgres caps a
  NOTIFY payload at 8000 bytes, so a large revocation cascade (roughly 190+ ids) used to be
  dropped with a WARN and the remote statements ran on to their own timeouts. The id set is now
  sent in batches of 100 (about 4 KB each), one NOTIFY per batch; receiver and payload schema are
  unchanged. Refs #86 (item 1).
- **Federation store connections now carry connect/socket timeouts, and restore() no longer
  resolves the federation blob for tenant-dbs that have no federated sources.**
  `FederatedSourceStore` opened a fresh JDBC connection per call with no `socketTimeout`, so a
  half-dead federation Postgres could hang any caller indefinitely - including manager boot,
  since `PoolSupervisor.restore()` re-resolves the federation blob per tenant-db on every boot
  and HA topology NOTIFY. The store's connections now carry `connectTimeout`/`socketTimeout`
  (10s/30s, never overriding a value the caller's URL already sets), `restore()` skips
  resolution entirely for a tenant-db with no federated sources (one cheap set-query per
  restore instead of a connection per tenant-db), and the resolution that does happen is bounded
  by a 15s timeout, degrading to the existing WARN-and-fallback path instead of hanging. Fixes
  #101.
- **`qod stop` sweeps an orphaned embedded control-plane postmaster** left behind by
  an abrupt JVM death (an orphan case mostly seen on Windows). The pid is checked
  twice before anything is signaled: a pid of 0 or negative is refused outright
  (those have process-group-wide meanings to `os.kill`, never a single targeted
  process), and the pid must still identify as a `postgres`/`postgres.exe` process
  (`ps -p <pid> -o comm=` on POSIX, `tasklist` on win32) before it is terminated,
  since the pid in a stale pidfile can be recycled by an unrelated process.
- **`qod serve` waits for a routable node** before printing its connect-string
  banner on a restart, instead of handing out strings that briefly fail while a
  respawned node is still warming up.
- **`qod serve`/`qod start` print a non-blocking one-line nudge** when a newer
  release is on PyPI.
- **Lockdown now covers the `qod serve` scheme aliases.** A lockdown-enabled node
  disables local filesystem access for the s3a/r2/gcs/azure/abfss dataPath scheme
  aliases, not just their s3/gs/az canonical forms.
- **`qod serve` notes a `--table` view's mismatched object-store scheme.** When a
  view's glob lives under a different scheme family than the target's own, it gets
  no scoped secret and falls back to the engine's ambient credential chain - now
  flagged instead of silent.
- **`qod serve <target>` provisions into an already-running LOOPBACK manager**
  instead of failing partway through a second JVM boot: it attaches in the
  foreground using the stored or exported admin password (never generating a
  fresh one, since that could not match a manager already up), or refuses with a
  clear message when no password is available. A manager running at a
  non-loopback URL is refused outright rather than attached to, since that is
  almost certainly a `qod login` profile against a remote deployment. `qod serve
  --demo` refuses outright when a manager is already running, since the demo's
  insecure posture must never land on someone else's gateway.
- **On Windows, an interrupted `qod serve`/`qod start` runs the stop sweep**
  before exiting, instead of leaving the shared console's Ctrl-C delivery to the
  child process as the only cleanup. (#100)

## 0.9.2

- **`qod serve <target>` takes you from your own data to a queryable gateway
  in one command.** Point it at a `.duckdb` file, a parquet/csv file, a
  directory or glob of them, an `s3://`/`gs://`/`az://` prefix, or nothing at
  all for a fresh empty DuckLake, and it provisions a tenant, database, and
  pool around it, then prints the client connection strings. Every step is
  ensure-semantics (create only what is missing), so a failed or interrupted
  run just resumes on re-run, and `qod serve ./other.duckdb` adds a second
  database beside the first instead of replacing it. Unlike `qod serve
  --demo`, this path is persistent and keeps the normal secure posture: TLS
  on, DB auth on, ACL on by default (overridable, with a loud warning), and a
  random admin password generated on the first run, printed once, and stored
  in the CLI config file (a real `QOD_ADMIN_PASSWORD` still wins); the saved
  CLI profile authenticates `qod sql` as the superuser realm out of the box.
- **The manager can run its control plane on a persistent embedded Postgres**
  (`QOD_PG_EMBEDDED`, single-node only - HA refuses to boot with it set)
  instead of requiring an external Postgres already running, which is what
  lets `qod serve` need nothing but a JVM. It lives at a fixed data
  directory and port (`--pg-port`/`--pg-data-dir` or
  `QOD_PG_EMBEDDED_PORT`/`QOD_PG_EMBEDDED_DATA_DIR`), persists across
  restarts, and is never deleted. `qod status` now live-probes it instead of
  just reading a pidfile.
- **`qod serve --demo` is now the canonical form of the self-contained demo**
  (embedded ephemeral Postgres, seeded TPC-H, deliberately insecure); `qod
  start --demo` still works, as a deprecated alias.
- **`qod database create --kind duckdb-file` defaults `dbName`/`schemaName`**
  to the database's own name and `main`, since neither is a real choice for a
  plain file - the DuckLake vocabulary was pure friction there. Anything the
  caller passes still wins.
- Fixed: `objectStoreSql` was only ever emitted for DuckLake tenant-dbs; every
  kind (including `duckdb-file` and `memory`) now gets its per-database
  `CREATE SECRET` when it carries object-store credentials.
- Fixed: a `memory`-kind tenant-db (loose parquet/csv views, no catalog) can
  now carry an object-store scope, needed for `qod serve` targets that serve
  views over a remote prefix.
- Fixed: pools rehydrated after a manager restart or HA NOTIFY lost their
  resolved federation blob (and, before that fix landed, their tenant-db
  kind); respawned and resumed nodes now boot with their federation ATTACH
  aliases intact.

## 0.8.5

- **The demo starts on arm64 instead of dying on missing Postgres
  binaries.** `qod start --demo` failed on Linux arm64 (Graviton, ARM VMs,
  containers on Apple Silicon) with zonky's `IllegalStateException: Missing
  embedded postgres binaries`. The build declared only `embedded-postgres`,
  whose transitive binary set is amd64 only, and zonky resolves binaries
  strictly from the classpath: its single emulation fallback covers
  Darwin/aarch64 and Windows on ARM, never Linux, so there was nothing left
  to try. The Linux and macOS arm64 binaries are now bundled. That also
  takes Apple Silicon off the Rosetta path, where the demo had been running
  an emulated Postgres behind a WARN the default `QOD_LOG_LEVEL=ERROR`
  hides, and failed outright when Rosetta was not installed. The uber-jar
  grows by about 40 MB.
- **`qod start --demo` no longer trips over the home a crashed run left
  behind.** A demo killed before its teardown (SIGKILL, machine sleep, OOM,
  or a second demo sharing the default `${TMPDIR}/qod-demo`) leaves
  `pg/pgdata` populated, and the next run's `initdb` refuses it with
  `directory "..." exists but is not empty`. That surfaced only as zonky's
  opaque `IllegalStateException: Process [...initdb...] failed`, since
  initdb's stderr goes to an INFO logger the default `QOD_LOG_LEVEL=ERROR`
  swallows, and the failed run's own cleanup then deleted the home, so the
  run after it succeeded and the whole thing looked random. `DemoHome.create`
  now clears the three subdirectories the demo owns (`pg`, `ducklake`,
  `native`, never the caller-supplied root) before creating them. Because
  that clean is destructive it refuses to run while a demo is still live on
  the home, keyed off Postgres's own `postmaster.pid` plus a pid-liveness
  check, and if the clean does not take it fails fast naming the directory
  to remove instead of landing back on the unreadable initdb error. A failed
  embedded-Postgres start now also reports the underlying cause rather than
  the wrapper exception.
- **jsqltranspiler 1.12 and jsqlparser 5.4.2.** The two move in lockstep
  because jsqltranspiler's pom pins the parser version. 1.12 carries the
  upstream fix overriding the `PivotQuery` visit methods that the 5.3 to 5.4
  visitor interface change required; 1.11 was built against 5.3.336 and
  would have run that path on an interface it does not implement.

## 0.8.4

- **The operator skill ships to users instead of living in the source
  tree.** Two install channels: `qod skill install` bundles it in the
  wheel and asks which LLM to install for (Claude Code, GitHub Copilot,
  Gemini CLI; `--platform claude|copilot|gemini|all` skips the prompt,
  `--project` targets the working directory, `--dir` names an exact
  target), and the repo is now a Claude Code plugin marketplace
  (`/plugin marketplace add starlake-ai/quack-on-demand`, then
  `/plugin install quack-on-demand@quack-on-demand`). The skill itself was
  rewritten to be checkout-free: every manager REST curl recipe is now the
  equivalent `qod` command, booting goes through `qod setup/start/status/stop`,
  and repo paths, sbt invocations and raw `qodstate` INSERT advice are gone.
  curl remains only where no CLI equivalent exists (the PyPI version probe,
  SCIM, federation YAML export/import). The operating agent also compares
  `qod --version` against PyPI and asks the user to upgrade when stale,
  never upgrading unprompted.
- **Bare `USE <schema>` now resolves against the tenant database.** On a
  node the DuckLake catalog is attached under the tenant-db name while the
  session's current catalog is the transient memory db, so a client issuing
  `USE star1` got "No catalog + schema named star1 found" even though
  `<dbName>.star1` existed, with no way to learn the physical database name.
  The edge now qualifies a bare one-part `USE x` into `USE <dbName>.x`.
  Two-part `USE a.b`, `USE <dbName>` and `USE memory` pass through
  untouched, so catalog switching still works.
- **Admin UI: the Nodes page shows the last 200 statements** instead of 50.
  The router's ring buffer holds 256 records and the endpoint caps at 500,
  so this is a display change only.
- **Admin UI: the "Starlake" nav entry is now labelled "Workbench".**

## 0.8.3

- **Manager creates the control-plane database at startup.** The boot
  preflight now classifies the initial connect failure: when the server is
  reachable but the control-plane database is missing (SQLState `3D000`), it
  is created through the admin database (`PG_ADMIN_DB`, default `postgres`)
  and boot proceeds, logging `control-plane database '<db>' did not exist;
  created it`. Every other failure (server unreachable, bad credentials)
  still refuses to start with the existing operator message. This removes
  the launchers' `psql` dependency: a fresh install needs only a Postgres
  user with `CREATEDB`. The `psql` preflight arms in `run-jar.sh` and the
  CLI remain as optional fail-fast conveniences.
- **Local rigs: reverted to SeaweedFS as the bundled object store** (the
  0.8.2 RustFS replacement is rolled back); the wrapper-script hardening
  from that work is retained (env precedence, data-path-derived seeding,
  port handling).
- **`qod sql --file` runs a SQL script fail-fast.** Statements split on
  top-level semicolons with full lexical awareness (strings, quoted
  identifiers, comments, dollar-quoting), executed in order; the first
  error aborts with exit 1 naming the statement and its line. `-` reads
  stdin.
- **`NUKE=1` asks for typed confirmation on a terminal.** All four launch
  surfaces (compose wrapper, `run-jar.sh`, the kind rig, `qod start`) now
  require typing the destruction target's name before wiping; non-tty runs
  skip the prompt, so scripts and CI are unchanged. There is deliberately
  no bypass env var.

## 0.8.2

- **`qod setup`: configure `qod start` once.** New CLI command persisting the
  env vars `qod start` reads (Postgres coordinates, admin credentials, API
  key, auth/TLS toggles, arbitrary `QOD_*`/`PROXY_*` via `--set`) into the
  CLI config file under a `[start]` table; guided prompts on a terminal,
  flags/`--set`/`--unset`/`--show` for scripts. A real shell export still
  wins, and `qod start --demo` deliberately ignores the stored config.
  Before saving, connection coordinates are verified (TCP probe, plus a
  `SELECT 1` login check when `psql` is on `PATH`); `--skip-checks` opts out.
- **`qod status`: one glance at what is running.** Manager `/health` and
  `/ready`, FlightSQL edge coordinates with a live port check, local manager
  pids, per-pool healthy/total nodes when logged in, and the stored setup
  summary. Exits 1 when the manager is unreachable, for scripting.
- **Local rigs: SeaweedFS replaced by RustFS** (kind local-stack and docker
  compose). BREAKING (dev rigs): the compose profile is now `rustfs` (was
  `seaweedfs`), data dir `./rustfs` (env `RUSTFS_DIR`), S3 endpoint
  `rustfs:9000`; a stale `seaweedfs` endpoint in `.env` warns instead of
  half-starting. RustFS does not auto-create buckets, so both rigs run a
  one-shot bucket-create step before the stack serves.
- **Compose wrapper hardening.** The wrapper script now honors the repo-wide
  precedence everywhere (process env wins over `.env`, incl. `PG_PORT` and
  profile auto-detection, scheme-tolerant endpoint matching); demo seeding
  derives its DuckLake data path from `QOD_DUCKLAKE_DATA_PATH` exactly as
  the manager does (trailing slashes and nested or local roots included), so
  S3-mode seeding no longer mismatches the catalog; data/cert/store dirs are
  prepared with correct ownership on every run, not only under `NUKE=1`.
- **Fix: demo seeding inside released images.** `scripts/_load-common.sh`
  was never copied into the Docker image after the loader refactor, so
  in-pod `LOAD_TPCH` seeding failed in every released image since; the
  image now ships it.
- **Helm chart published as an OCI artifact.** Every release pushes the
  chart to `oci://ghcr.io/starlake-ai/charts/quack-on-demand`, stamped to
  the release version; the chart's node-pod image now defaults to the
  release `appVersion` instead of `latest-snapshot`, so a versioned chart
  deploys versioned images throughout.

## 0.8.1

- **MCP full admin surface.** The MCP endpoint (`POST /mcp`) now exposes the complete
  admin control plane to agents: tenants, users, groups, roles, memberships, role table
  permissions, column/row policies, pool permissions, pool lifecycle and settings,
  tenant-dbs, maintenance policies, tags (including delete and protect/unprotect),
  restore/undrop, federated sources and secrets, manifest export/import, self-scoped
  PATs, server config, and statement/usage telemetry - 69 new tools (91 registered in
  total) across the identity, access, and platform tiers. This supersedes the
  2026-08-18 deny-list decision: destructive operations are exposed and rely on the
  same server-side guards REST uses (superuser gates, tenant scope checks, self/floor
  guards, mutation gates, audit). `protect_tag` now toggles both directions and
  `delete_tag` is available; PAT tools remain self-scoped (an agent manages only its
  own token subtree). A coverage spec (`McpCoverageSpec`) now fails the build if a
  REST mutation route is added without an MCP tool or an explicit exclusion.
- **SQL admin dialect: audit and history parity with REST.** Admin statements
  executed over FlightSQL (`GRANT`, `CREATE ROLE`, `CREATE USER`, policy DDL,
  ...) now land in statement history and metrics with the SQL redacted, and
  emit the same `AuditActions` events REST already emits for the equivalent
  call - including denials and parse failures, which previously left only a
  WARN log line.
- **Fix: a claim-shaped statement on a non-dialect path could leak a
  password into history.** A statement that *looked* like admin SQL (e.g.
  `CREATE USER ... PASSWORD '...'`) on a path the dialect does not serve -
  MCP, or `QOD_SQL_ADMIN_ENABLED=false` - recorded the raw SQL in statement
  history when denied, and when admitted and forwarded, the node's parser
  error (which quotes the statement, password literal included) was recorded
  in the history error column.
  Both the SQL and the error text are now redacted for claim-shaped
  statements on every path, not just the ones the dialect actually executes.
- **Parser: dollar-quoted and E-string literals now tokenize inside
  expressions.** `$$...$$` and `E'...'` in a row/column policy expression
  used to make `claims()` return false, so the statement fell through to the
  node and failed with a confusing DuckDB parser error instead of an admin
  one. Comments are now rejected everywhere in an expression body instead of
  being accepted in some shapes and not others.
- **`CREATE OR REPLACE ROLE` is now claimed** and answered with a proper
  admin error instead of falling through to a raw DuckDB parser error.
- **`GRANT ... TO USER` on a table grant now gives a targeted error.**
  `GRANT SELECT ON t TO USER alice` used to absorb `USER` as the role name
  and fail with a confusing "unexpected trailing input: 'alice'"; it now
  names the actual problem (roles only, not `USER`/`GROUP`, in the table
  grant arm).
- **New statements:** `SHOW GRANTS FOR USER u` (the user's effective grant
  set, flattened through role and group membership), `ALTER USER u REQUIRE
  PASSWORD CHANGE`, and `ALTER USER u ENABLE` / `DISABLE`.
- **BREAKING (narrow): `POST /api/user/update` with `mustChangePassword` and
  no `password` now succeeds.** It previously returned `400
  invalid_argument`, refusing a flag-only update. Setting
  `mustChangePassword` (or `enabled`) without also sending a new password is
  now a supported, standalone update.

## 0.8.0

- **SQL admin dialect over FlightSQL.** Admin-gated SQL statements sent
  through the FlightSQL edge are now answered directly by the manager and
  never forwarded to a node: `GRANT` / `REVOKE` table ACLs, row and column
  policies (including masking), role/group management and membership,
  `GRANT` / `REVOKE` pool `CONNECT`, and user management (`CREATE USER`,
  `ALTER USER ... PASSWORD`, `DROP USER`). `SHOW` introspection covers roles,
  grants, row/column policies, pool grants, and `SHOW USERS`. Behind
  `QOD_SQL_ADMIN_ENABLED` (default on); excluded from the MCP server, which
  stays REST/CLI-oriented.
- **Fix: mutating SQL admin statements failed through the prepared FlightSQL
  path.** `createPreparedStatement` advertised the generic DML/DDL `Count:
  int64` dataset schema for every claimed admin mutation (`CREATE ROLE`,
  `GRANT`, `CREATE USER`, ...), since they classify as DDL; ADBC/JDBC's strict
  prepare-time schema validation then rejected the real `(status, detail)`
  Execute result, so the dialect could not be driven through its primary
  clients at all. A claimed mutation now advertises the correct `(status,
  detail)` schema at Prepare, still without executing. Also unifies every
  mutation's result to exactly `(status, detail)`: `REVOKE`'s result no longer
  carries a separate `revoked` column - the count now lands in `detail` (e.g.
  `"revoked 2"`).
- **Revoking a personal access token now also kills its live statements.**
  `POST /api/auth/pat/revoke` (and `qod auth pat revoke`) still cascades the
  revocation over the token's whole minted subtree, and now additionally kills
  every in-flight statement issued with any of those tokens: synchronously on
  the replica serving the call, and on the other replicas of an HA cluster via
  a new `qod_pat_kill` LISTEN/NOTIFY channel (best-effort, typically
  milliseconds). Killed statements land in statement history with status
  `killed`; the `AuthPatRevoke` audit event gains `revokedCount` and
  `killedStatements` detail entries. BREAKING (wire shape): the revoke
  endpoint's 200 body was empty and is now
  `{"status":"ok","killedStatements":N}` where N counts the serving replica's
  kills. A failed kill or broadcast never fails the revoke: the tokens are
  already dead and running statements stay bounded by their statement
  timeouts.
- **Fix: a row/column policy predicate carrying a trailing SQL comment could
  silently disable RLS/CLS.** `RowPolicyRewriter` and `ColumnPolicyRewriter`
  splice a validated predicate/transform textually into generated SQL; a
  trailing `-- comment` (or a `/* */` block comment, or a bare `;`) commented
  out the rewriter's own closing parenthesis at splice time, the re-parse
  threw, and the statement was forwarded unfiltered. `RowPredicateValidator`
  and `TransformSqlValidator` now reject any `predicateSql`/`transformSql`
  containing a comment marker or a bare `;` outside string literals at
  create/update time (BEHAVIOR CHANGE: such values now fail policy
  create/update where they previously succeeded and silently broke
  filtering). For rows already stored before this fix, `RowPolicyRewriter`
  gains a `Failed` outcome: a predicate that cannot be applied at rewrite
  time now denies the statement (fail-closed) instead of forwarding it
  unfiltered.

## 0.7.2

- **Security: six SQL-authorization bypasses closed after a deep code review.**
  A whole-codebase review found and this release fixes, each with pinning
  tests: (1) a WITH-prefixed INSERT/UPDATE/DELETE/MERGE could launder a read
  of any table through a CTE named after a granted table - the ACL parser now
  walks statement-level WITH clauses; (2) the row-level-security rewriter
  passed DuckDB `FROM t` shorthand, parenthesized joins, and every
  expression-position subquery (EXISTS / IN / scalar / HAVING) through
  unfiltered - the walk is now depth-complete; (3) WITH-prefixed DML and
  `EXPLAIN ANALYZE <dml>` were classified as reads, skipping the
  protected-write guard, RLS/CLS rewriting, write audit, and writer routing -
  the classifier now resolves the statement's real verb; (4) lockdown pools
  admitted local-file replacement scans through dollar-quoted (`$$...$$`) and
  escape-string (`e'...'`) literals in FROM position - both now deny unless
  provably remote; (5) the login endpoint distinguished "User not found" from
  "Invalid password" in both message and timing, an account-enumeration oracle
  that also enabled targeted lockout denial-of-service - the two failures are
  now indistinguishable on the wire, and raw JDBC error text no longer reaches
  unauthenticated callers; (6) a tenant admin who pointed their tenant's OIDC
  at an IdP they control could mint a token carrying the seeded superuser's
  username and obtain an unrestricted data-plane session bypassing ACL,
  RLS/CLS, and lockdown - a credential validated in a tenant realm can no
  longer bind to a superuser row. Multi-tenant deployments should upgrade.
  Module authors: `PoolSupervisor.authorizeHandshake` gained a defaulted
  parameter (source-compatible, binary-incompatible) - recompile modules
  against this core.

- **SCIM 2.0 provisioning.** `/api/scim/v2/{tenant}` serves RFC 7643/7644
  Users and Groups over the RBAC store for IdP connectors (Okta, Entra):
  CRUD, `eq` filters, pagination, PATCH (active toggle, member ops), and the
  discovery endpoints. `Authorization: Bearer` (static key or PAT) is accepted
  on the SCIM prefix only. The superuser realm is invisible to SCIM.

- **Idle hibernation in core.** A leader-gated sweep
  (`quack-on-demand.hibernation`, `QOD_HIBERNATE_*`) suspends a pool that has
  served no statements for its idle window, with per-pool opt-in via
  `Pool.idleTimeoutSec` (or a manager-wide `defaultIdleMinutes`). Inert on a
  fresh install; the FlightSQL edge still wakes a suspended pool on the first
  statement.

- **Scoped personal access tokens.** A PAT can now carry a restriction
  (pools, statement kinds, row caps, timeout) that attenuates the owner's
  effective set without ever widening it; tokens record their parent chain
  and revocation cascades to children. `qod pat create` grows the matching
  scope flags, the profile UI shows the full scope on hover, and audit
  records resolve PAT bearers to their owning principal.

- **Lockdown pools also deny resource settings.** `SET`/`RESET`/`PRAGMA` on
  `memory_limit`, `max_memory`, `threads`, `worker_threads`, and
  `max_temp_directory_size` are refused for non-superusers on lockdown pools,
  closing a resource-abuse vector (raising memory past the node's spawn-time
  default). Known limitation: the name match is statement-wide, so a
  statement that merely mentions a protected name (a table named `threads`,
  a literal containing `memory_limit`) is over-denied on lockdown pools.

- **RLS: quoted table identifiers no longer bypass row policies.** jsqlparser
  keeps double quotes on quoted identifiers, and the policy match compared
  the quoted form - Power BI-style folded SQL (`"tpch1"."customer"`) escaped
  its row policies entirely (fail-open). Matching now unquotes; the rewritten
  SQL keeps the caller's original quoting.

- **Docs moved to starlake-docs.** The standalone website is retired; the
  README now leads with the zero-commitment demo command.

## 0.7.1

- **Fixed `qod start` printing nothing (0.7.0 regression).** The supervised
  launcher introduced in 0.7.0 relays the manager's output through a pipe, and
  it read that pipe with `read(4096)`, which blocks until 4 KB has accumulated.
  The manager's startup banner is a few hundred bytes, so it stayed buffered
  while the manager sat there serving normally: `uvx qod start` looked hung on a
  blank screen even though `http://localhost:20900` was up. The relay now uses
  `read1`, which hands over whatever has arrived, so output appears as the
  manager emits it. `scripts/run-jar.sh` was never affected (it relays through
  `cat`). Affects 0.7.0 only.

## 0.7.0

- **`qod start` no longer fails when offline.** The manager jar is resolved from
  GitHub Releases on every run, so a machine with no network (a plane, a locked-down
  VPN, `uvx qod start` on a fresh shell) used to hard-fail even with a perfectly
  good jar in the cache. The release lookup now falls back to a cached jar: the
  release this CLI build pins when that one is cached, otherwise the newest cached
  version, naming what it picked and where the cache is. `--version latest` takes
  the same path instead of surfacing a raw connection traceback. An explicit
  `--version X.Y.Z` is still never substituted; when it is neither cached nor
  downloadable the error now lists the versions that are cached. An empty cache
  with no network remains an error. Cache locations (`~/Library/Caches/qod/jars`
  on macOS, `~/.cache/qod/jars` on Linux, `%LOCALAPPDATA%\qod\Cache\jars` on
  Windows, `JAR_CACHE_DIR` to override) are now documented in the install guide.

## 0.6.9

- **Ctrl-C on a foreground launcher now acts as `qod stop`.** Both `qod start`
  (incl. `--demo`) and `scripts/run-jar.sh` supervise the manager JVM instead of
  exec-ing it: Ctrl-C (and `kill`/a closed terminal) triggers the graceful
  teardown - SIGTERM manager + nodes, bounded wait (`FORCE_AFTER`, default 10s),
  SIGKILL sweep - so an interrupted run no longer orphans duckdb node processes
  on ports 21900+. This also fixes a long-standing "Ctrl-C does nothing" bug:
  the JVM tree could flip the terminal into raw mode during boot (ISIG off),
  after which Ctrl-C stopped generating SIGINT entirely; the launchers now route
  the JVM's stdio through pipes so it never holds a terminal fd. The exit code
  is the JVM's own on a normal exit, 130 after an interrupt teardown. Windows
  (`run-jar.ps1` / `qod start` on win32) keeps the previous behavior.

- **Personal access tokens can be deleted.** A revoked or expired PAT stays in
  the listing until its owner discards it: `POST /api/auth/pat/delete`,
  `qod pat delete --id <id>`, or the profile page's Delete button (shown on dead
  rows where live rows show Revoke). Only dead tokens are deletable - a live id
  is refused `400 pat_live` (revoke remains the sole kill switch), and someone
  else's or an unknown id answers `404 not_found` with no existence leak, same
  contract as revoke. Deletion is audited as `auth.pat.delete` and rides the
  same self-scoped session-only surface (and non-admin profile allowlist) as
  the other PAT verbs.

- **Admin UI ergonomics.** The login screen refills the Tenant field from the
  last successful sign-in (stored client-side, saved only on success); the
  tenant filter comboboxes (Usage, Statements, Control Plane, Nodes, Catalog,
  Users) preselect the tenant the session signed in with - deep-link `?tenant=`
  still wins and a system-scope login keeps today's defaults; and the tenant
  detail page's tab order is now Databases, Pools, Maintenance, Auth provider.

- **Metrics for the metadata-filter and protected-write steps.** The FlightSQL
  pipeline now records `metadata_filter_rewrites_total{outcome}` /
  `metadata_filter_duration_seconds` and `protected_write_checks_total{outcome}` /
  `protected_write_check_duration_seconds` (per tenant/pool), so operators can see
  how often statements are filtered or denied by those steps and their latency,
  matching the existing column/row-policy rewrite metrics.

- **Closed a column/row-security bypass (S1).** A user restricted by a column mask
  or row policy could previously launder the unmasked/unfiltered data by wrapping
  the read in a write (`CREATE TABLE ... AS SELECT`, `INSERT ... SELECT`,
  `CREATE VIEW ... AS SELECT`, and via subqueries in `MERGE`/`UPDATE`/`DELETE`),
  because masking/filtering only ran for plain `SELECT`. Such statements are now
  denied when their read side exposes a protected table: any read of a row-policy
  table, and a masked-column read of a CLS table (a write that reads only unmasked
  columns is still allowed, detected by running the write's inner SELECT through the
  real column-policy rewriter; any read the guard cannot isolate fails closed).
  Enforced whenever CLS/RLS enforcement is enabled (independent of `acl.enabled`,
  like the CLS/RLS rewriters themselves); superusers are unaffected.
  Closing this required making the read extractor complete-by-construction, which
  also tightens table-level ACL: a table read previously hidden from the grant check
  inside an `INSERT ... VALUES` subquery, a VALUES-derived FROM table, an expression
  wrapper (`ANY(ARRAY[...])`, `COLLATE`, and the like), or a Select-clause subquery
  (`GROUP BY`, `DISTINCT ON`, `QUALIFY`, named `WINDOW`, `GROUPING SETS`) is now
  grant-checked, so a principal without `RO` on such a table now gets a deny where
  the read used to slip through. Known limitation: a masked column referenced only in
  a `JOIN ON` condition of a write's read side is not detected (an inference-only
  channel, matching the base CLS SELECT path), and write-side row enforcement
  (scoping a policy-holder's own `UPDATE`/`DELETE` on a row-policy table to their
  rows) is a separate open follow-up, not part of this fix.

- **Filtered metadata.** Any authenticated principal can read the session database's
  `information_schema` (schemata/tables/columns/views) without a grant; result rows are
  filtered to the objects the principal holds at least RO on, so table-level ACL stays
  meaningful (no enumeration of ungranted objects). This also fixes catalog browsing in
  JDBC/ADBC clients: the FlightSQL catalog RPCs (`GetCatalogs` / `GetDbSchemas` /
  `GetTables`, the DBeaver and ADBC table-tree path) run as `information_schema` queries
  under the hood and so came back DENIED for any principal without an explicit grant;
  they now return the grant-scoped tree. Explicit `information_schema` grants keep
  today's unfiltered read; `QOD_ACL_FILTERED_METADATA=false` restores the old
  grant-required posture. Also closed: `DESCRIBE <t>` / `SHOW <t>` / `SHOW COLUMNS FROM
  <t>` previously bypassed the ACL entirely and now require RO on the target (applies
  whenever ACL is on, independent of the new flag); plain `SHOW TABLES` answers the
  filtered listing, and its `FROM`/`IN`/`LIKE` variants are denied while the filter is
  active (query `information_schema.tables` instead). `SHOW ALL TABLES` is unchanged
  (already denied under ACL). The filter is fail-closed: an `information_schema`
  reference in a position it cannot rewrite (ORDER BY, GROUP BY, a window clause) or a
  filterable name merely mentioned in a string literal is denied rather than passed
  through unfiltered.

- **Admin user lock.** The edit-user panel (and `qod user update --no-enabled` / the
  `enabled` field on `user/update`) can lock any account: sign-in is refused, tokens
  stop working, and only an admin can unlock. A superuser may lock any user, a tenant
  admin only their own tenant's users; locking yourself and locking the last enabled
  superuser are refused, and `user/delete` now carries the same two guards, so a
  deployment can never lock or delete itself out entirely. Note: a manager restart
  re-creates a DELETED seeded admin but does NOT unlock a LOCKED one; recovery from a
  lock is another admin, the static API key, or SQL. Any lock/unlock write also resets
  the account-lockout counters, like every other admin write to a user row.

## 0.6.6

_Released 2026-08-18._

- **BREAKING: an unset or empty `QOD_API_KEY` no longer leaves `/api` open.** Every
  non-public `/api` call now requires a session, a personal access token, or the static key;
  a keyless call answers 401 regardless of configuration. Keyless dev scripts must log in via
  `/api/auth/login` (or set `QOD_API_KEY`). Public endpoints (login, password reset, client
  config) are unaffected.
- **MCP server for AI agents.** The manager now serves an MCP (Model Context Protocol)
  endpoint at `POST /mcp` on the REST port (stateless Streamable HTTP, `QOD_MCP_ENABLED`
  default on), so agents like Claude Code can discover schemas, run SQL with full
  RBAC/RLS/CLS enforcement, use DuckLake time travel, and (with an admin credential)
  operate pools and nodes. Data tier: `run_sql` (row-capped via `QOD_MCP_MAX_ROWS`,
  default 500), `list_databases`, `list_tables`, `describe_table`, `table_history`,
  `list_snapshots`, `my_usage`. Admin tier: pool status/scale/suspend/resume, node
  restart/quarantine, active statements + kill, maintenance, tag create/protect
  (protect-only), audit search. Destructive and credential operations are deny-listed
  with no code path from `/mcp`. Auth is a personal access token or the static API key;
  session JWTs are refused.
- **Personal access tokens (PATs).** Long-lived bearer credentials for agents and
  scripts, minted from the profile page (UI), `qod auth pat create|list|revoke` (CLI),
  or `POST /api/auth/pat/*` (REST). Only a SHA-256 hash is stored; the token is shown
  once at mint. A PAT is accepted wherever a session token is, on both `/api` and
  `/mcp`, with exactly its owner's scope (admin PATs manage; `role=user` PATs are
  profile-only). PAT management itself is session-only: a PAT can never mint,
  enumerate, or revoke tokens.

## 0.6.5

_Released 2026-08-17._

- **Security (please upgrade): federated secrets with an external reference are now
  superuser-only.** A federated secret whose `externalRef` uses `env:` / `aws-sm:` /
  `gcp-sm:` / `azure-kv:` / `vault:` is resolved from the MANAGER's own trust domain at node
  spawn (`env:` reads the manager process environment; the KMS prefixes use its ambient
  credentials) and the resolved value is inlined into the tenant's node setup SQL, which the
  tenant can read back. A tenant admin could therefore author `env:QOD_SESSION_JWT_SECRET` on
  their own database, read it out of a node table, and forge a superuser session. Authoring an
  `externalRef` secret over REST/CLI now requires a superuser session
  (`403 superuser_required`); tenant admins keep value-backed (inline) secrets, and
  operator-run bootstrap / manifest imports (already superuser-gated) are unaffected. All
  0.6.x releases carry this; upgrade if tenant admins can manage federation.
- **Pool lifecycle actions moved to the pool detail page.** Scale, a Suspend menu
  (Hibernate / Drain / Kill), and Delete now live in the pool detail header instead of the
  tenant Pools list, which drops its Actions column. Scale keeps its Force option when
  shrinking; Hibernate suspends to zero nodes and auto-wakes on the next query.
- **CLI default profile.** `qod` now remembers a sticky default profile and adds a profiles
  listing, so repeated commands no longer need the connection flags spelled out each time.
- **Lockdown denies direct access to DuckLake buckets.** With node lockdown on, object-store
  access is denied at bucket granularity: the derived deny-set covers each tenant-db's
  DuckLake buckets plus the managed bucket, closing direct bucket reads that prefix rules
  alone did not.
- **Fixes.** The assembly bundles Jakarta Mail's registry files, so SMTP send no longer fails
  on a missing provider registry; the boot sequence defers the mutation-gate read until after
  modules start; `run-jar.sh` sources an untracked `.env.local` for local overrides.
- Project status is now marked Stable (was Beta), and the docs note opt-in active-active HA
  on Kubernetes.

## 0.6.4

- **Account lockout (opt-in).** After a configurable number of failed logins a database
  account locks. `QOD_AUTH_LOCKOUT_ENABLED` (default off) turns it on and
  `QOD_AUTH_LOCKOUT_MAX_FAILURES` (default 10) sets the threshold; enabling it requires an
  SMTP server to be configured (the manager refuses to boot otherwise, naming
  `QOD_SMTP_HOST`). Only users that carry an email are ever locked, so a user with a
  non-email username and no email is never affected. Enforced across REST login, the
  FlightSQL handshake, and the change-password endpoint; a locked login answers
  `401 account_locked`. Lockout does not revoke already-issued sessions.
- **Self-service password reset over email.** New public `POST /api/auth/forgot-password`
  and `POST /api/auth/reset-password` (also `qod auth forgot-password` / `reset-password`)
  let a user reset their own password through a single-use, time-limited link emailed to
  their address. Configure SMTP with `QOD_SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` /
  `_FROM` / `_STARTTLS`, and `QOD_PUBLIC_BASE_URL` for the link's origin. The admin UI gains
  a "Forgot password?" link and a reset page. This is the recovery path for a locked
  account.
- **Optional email on database users.** `qodstate_user` gains an `email` column, settable on
  `user/create` and `user/update` (`qod user --email`, admin UI field). When a username is
  itself in email format, it IS the user's email (auto-assigned and immutable); setting a
  different email returns `400 invalid_email`. Existing email-format usernames are backfilled.
- **Operator notes.** Enabling lockout requires `QOD_SMTP_HOST` and `QOD_PUBLIC_BASE_URL`.
  A locked superuser recovers by restarting the manager (the admin re-seed clears the lock
  and resets to `QOD_ADMIN_PASSWORD`) or via the static `X-API-Key`; set `QOD_ADMIN_USERNAME`
  to a real deliverable address if you want the seeded admin to self-recover by email.

## 0.6.3

- **Security (please upgrade): unauthenticated control-plane bypass fixed.** The REST
  API-key guard matched the raw percent-encoded request path while the router matched the
  decoded one, so a spelling like `GET /%61pi/tenant/list` or `GET ////api/tenant/list`
  (four or more leading slashes) skipped the guard entirely and still reached control-plane
  read AND write endpoints without a key or session. All 0.5.x and 0.6.0-0.6.2 releases
  carry this hole when `QOD_API_KEY` is set. The guard now derives its path from the same
  decoded segments the router uses (leading empties dropped), with regression tests pinning
  both spellings. Deployments exposed beyond localhost should upgrade.
- **Forced password change at next login.** A super or tenant admin can flag a user's
  password as temporary at create or reset time (`mustChangePassword` on `user/create` and
  `user/update`, `--must-change-password` in the CLI, a checkbox in the admin UI). Until the
  user changes it, both the REST login (`401 password_change_required`) and the FlightSQL
  handshake refuse it. A new public pre-session `POST /api/auth/change-password` (also
  `qod auth change-password`) lets the user set a real password: current password is the
  credential, the new one must differ. The database auth queries now MUST project a fourth
  `must_change_password` column - a shorter custom `QOD_AUTH_DB_SYSTEM_QUERY` /
  `QOD_AUTH_DB_TENANT_QUERY` fails at boot with an actionable error.
- **Regular users can sign in to a profile page.** A tenant-scoped `role=user` login now
  mints a profile-only session instead of being refused: the admin console stays admin-only,
  but regular users land on a Profile page where they can change their own password and view
  their own usage and recent statements (`qod profile usage` / `qod profile statements`).
  Non-admin sessions are demoted at the API guard to an exact allowlist (whoami, logout,
  the two profile endpoints); every other endpoint answers `403 admin_required`, and those
  denials are now audited. The system (tenant-less) login and OIDC SSO remain admin-only.

## 0.6.2

- **DuckDB driver + node engine alignment.** duckdb_jdbc bumped 1.5.5.0 to 1.5.5.1
  (driver-only patch: auto-close of the getResultSet result; engine stays 1.5.5). The
  K8s node image now bakes DuckDB CLI 1.5.5 (it had lagged at 1.5.4 through the 1.5.5
  cycle) and the pin-parity spec guards the Dockerfile so a future engine bump that
  misses the node image fails the suite.
- **Node image released in lockstep.** Every release now publishes
  `starlakeai/quack-on-demand-node:<version>` (plus minor/major/latest tags) alongside
  the manager image, built from the release tag. Before this, the node image only ever
  had -SNAPSHOT tags; 0.6.1's node tag was minted retroactively from the
  benchmark-validated snapshot digest. The Helm chart docs now recommend pinning the
  release tag; the default stays latest-snapshot.

## 0.6.1

- **K8s registry-drift hardening.** The pod set and the `qodstate_node` registry now converge
  instead of wedging when they drift (node-group replacement, crash mid-teardown, pool
  delete/recreate): teardown stops are best-effort (a dead pod or apiserver error can no
  longer block row cleanup; the warn names the possibly-leaked pod), reconcile gets real k8s
  liveness via one labeled pod-list per pool (missing pods respawn up to the pool's
  distribution; DEAD rows beyond that distribution are pruned every tick, while a live-pod
  overage is left alone and only logged as a warn - an HA replica with a stale, lagging
  distribution must never stop and delete healthy pods), and `pool/delete` and manifest
  imports sweep DB-side node rows before the pool row (FK RESTRICT kept as the last-line
  invariant). `KubernetesQuackBackend.stop` waits for actual pod deletion (new
  `k8s.stopTimeoutSec` / `QOD_K8S_STOP_TIMEOUT_SEC`, default 60) while `start` retries a
  create that 409s against a Terminating twin, no longer tears down a pre-existing incumbent's
  Service/Secrets on a failed start (the incumbent's token-Secret value is restored too, so
  manager-restart adoption keeps authenticating), and creates the per-node Service idempotently (an
  out-of-band pod death can leave the Service behind; a respawn on the same node id updates it
  in place instead of 409ing). `database/update` rolls now report per-node failures instead of
  aborting with a bodyless 500, and `node/restart` maps backend errors to a structured 502. The
  federation Secret is GC'd even when the pool's last pod is already gone.

## 0.6.0

- **Cache-aware query routing (directory-lite).** Repeat queries over the same tables stick to
  the node that already holds their data on object-store pools, with a load cap bounding skew.
  Each table gets up to three warm "homes" (spread by overflow, invalidated by write epochs);
  routing picks the warmest eligible node and falls back to least-loaded everywhere else.
  New config: routing.cacheAware (QOD_ROUTING_CACHE_AWARE, default true),
  routing.loadCapFactor (QOD_ROUTING_LOAD_CAP_FACTOR, default 2.0). New metrics:
  routing_tables_total, routing_placements_total, routing_decisions_total, routing_load_ratio.
  Setting cacheAware=false instantly reverts routing decisions to least-loaded; the locality
  metrics and their memoized statement parse keep running (they are the milestone-1 baseline
  and are deliberately ungated). Pinned statements (transaction pin or soft preferredNode) are
  labeled pinned-sticky/pinned-move in routing_decisions_total, keeping the overflow-evict-home
  build trigger clean; HA followers clear placement state on peer-driven pool deletes.
- **Docs: HA is documented as supported.** operating/resilience.md now describes the opt-in
  Kubernetes HA mode (advisory-lock singleton duties, per-pool locks, LISTEN/NOTIFY cache
  propagation) instead of calling two managers unsafe, and lists the routing caches among the
  per-manager in-memory state rebuilt from traffic.
- **Two deployment-shaped Grafana dashboards.** grafana-dashboard.json is replaced by
  grafana-dashboard-single.json (one-box docker-compose stack) and grafana-dashboard-k8s.json
  (multi-node / Kubernetes, adds pool occupancy, node health, and the new routing locality row).
  Both gain DuckLake maintenance and CLS/RLS rewrite panels that were never dashboarded before.

## 0.5.3

- **Per-pool node lockdown override.** `qodstate_pool` gains a tri-state
  `lockdown` (`inherit | on | off`, default inherit = the global
  `QOD_NODE_LOCKDOWN` flag), resolved once per pool and enforced by BOTH
  layers (edge screen per statement, engine SQL at node spawn). Superuser-only
  `POST /api/pool/setLockdown` persists the override and restarts the pool's
  nodes; `pool/create` takes a `lockdown` field (superuser-gated when not
  `inherit`) surfaced as `qod pool create --lockdown` and a pool-detail UI
  toggle; manifests export/import the override (and now `suspended` too).
- **libquackwire is vendored in-repo; Maven Central publication retired.**
  The native JNI binaries for all five platforms live in git under
  `libquackwire/binaries/` (with sha256 companions and a VERSION stamp) and
  are packaged straight into the assembly - no classifier jars, no Sonatype,
  no GPG. `scripts/refresh-quackwire-binaries.sh` rebuilds the host platform
  and pulls the rest from CI; releases verify the vendored set instead of
  querying Central. `QOD_WITH_WINDOWS_NATIVE` is retired: `quackwire.dll` is
  bundled automatically whenever it is vendored (it currently is).
- **Tag-driven release workflow; CI is the sole publisher.** Pushing a
  `v<X.Y.Z>` tag cuts the full release from GitHub Actions (jar + sha256,
  GitHub release, PyPI `qod` + `qod-cli` with a version-drift guard,
  multi-arch Docker, Discord announce, next-snapshot bump PR).
  `scripts/release.sh` now only verifies, stamps the release versions, tags,
  and pushes; `release-jar.sh` is retired and `release-docker.sh` is a manual
  fallback.
- **Per-database object-store credentials take effect at runtime.** A tenant-db's
  `objectStore` map now authors a DuckDB secret scoped to that database's
  `dataPath` at node spawn, so each database authenticates its own bucket with
  its own keys alongside the process-global `QOD_S3_*`/`QOD_AZURE_*`/`QOD_GCS_*`
  defaults (DuckDB picks the most specific scope). On Kubernetes the resolved
  SQL lands in a per-node Secret injected via `secretKeyRef`, never inlined in
  the pod spec; REST responses redact the secret keys; semicolons are rejected
  in credential values; editing `objectStore` restarts the database's nodes so
  rotation applies immediately.
- **Operator security hardening.** `QOD_NODE_LOCKDOWN=true` adds a two-layer
  lockdown (edge denial of ATTACH/INSTALL/protected SET/local-file read
  functions for tenant sessions + engine value-sets before `quack_serve`);
  spawned K8s node pods get non-root / seccomp / read-only-rootfs security
  defaults; an opt-in helm NetworkPolicy (default-deny node + manager
  policies); and cached catalog readers are evicted after idle to bound
  Postgres connections. See the new operator hardening guide.
- **DuckDB upgraded 1.5.4 -> 1.5.5** across every pinned layer: libquackwire
  rebuilt against libduckdb 1.5.5 (`1.5.5-7e80f7ffcc98-1`, duckdb-quack
  submodule at the v1.5-variegata head - brings fetch-batch pushdown fixes,
  catalog fetch truncation fix, INSERT RETURNING rejection, and secret-sourced
  quack_serve tokens), the DuckDB JDBC driver (`1.5.5.0`), and the runtime
  libduckdb / DuckDB CLI fetched by `run-jar.sh` / `run-jar.ps1` and the
  `qod` Python CLI.
- **quackwire macOS CI now links the official libduckdb release zip** instead
  of Homebrew (whose bottle lags DuckDB releases).
- **`QOD_VERSION=BUILD` links the local libquackwire rebuild against the
  pinned libduckdb** from the `.duckdb/<version>` cache (staging the internal
  header tree once per version) instead of the operator's system install.
- **CLI-bundled spawn scripts re-synced** with the canonical ones (they were
  missing the per-db object-store and lockdown blocks, so `qod start` nodes
  silently lacked both).
- **Pin-bump checklist partially automated**: engine verb renames, sched-join
  drift, and launcher-pin drift now fail the suite instead of silently
  misclassifying history.

## 0.5.2

- **`ManagerContext.sessionOf`** module SPI hook for portal-style modules
  (resolve the session behind a request without re-implementing cookie/token
  parsing).
- **`qod start` fixed on Windows.**

(Released 2026-07-20 without a changelog section; reconstructed retroactively.)

## 0.5.1

### Module SPI: mutation gates

- **Modules can now veto resource growth.** The manager module SPI gains
  `MutationGate`: a module contributes gates via
  `ManagerModule.mutationGates`, and the supervisor consults them before
  `createTenantDb`, `createPool`, and pool scaling. A refusing gate surfaces
  as HTTP 429 with code `quota_exceeded` and the gate's reason string in the
  body, so hosted-service modules can enforce per-tenant structure quotas
  (max databases, max pools, max nodes per pool) without any policy living in
  core. Zero-module boots are unchanged: no gates, no new behavior.
- **Operators always pass.** Superuser sessions and static-key callers bypass
  gates entirely, so a broken or overly strict quota policy can never lock
  operators out of provisioning. A gate that throws refuses the mutation
  (fail closed): a broken policy store must not grant unlimited resources.
- **Session scope for modules.** `ManagerContext` gains `scopeOf`, the same
  session-token-to-scope resolver core handlers use for tenant scoping, so
  modules can gate their own endpoints with the manager's session semantics
  instead of reinventing auth.

## 0.5.0

### Scale to zero

- **Pools can now hibernate.** `qodstate_pool.suspended` marks a pool scaled to
  zero WITH its role distribution kept: suspend gracefully drains every node
  (in-flight statements finish) while the DuckLake catalog and data stay
  intact, and the stored distribution is the memory of what to respawn. Wake is
  automatic: the first FlightSQL statement against a suspended pool resumes it
  and the edge holds the query until a node is routable, bounded by
  `PROXY_RESUME_HOLD_TIMEOUT_SEC` (default 60s), then fails retryable ("pool is
  resuming, retry shortly"). No client changes needed; the first query after a
  sleep just looks slow once.
- **Suspend on demand.** `POST /api/pool/suspend` / `POST /api/pool/resume`
  (tenant-scoped, audited as `pool.suspend` / `pool.resume`), a Hibernated
  badge plus Suspend/Wake button in the UI, and `startSuspended` on pool
  create: provision a pool with its catalog initialized but zero nodes until
  first use.
- **Guard rails.** Scaling a suspended pool is rejected with 409
  `pool_suspended` (resume first); stopping a suspended pool zeroes the
  distribution and clears the flag so it genuinely stays down; `disabled`
  still wins over everything and is never auto-woken.
- **Self-healing.** Reconcile skips suspended pools' respawn, heals a crash
  mid-suspend by draining straggler nodes, refreshes pool state under the
  per-pool lock so it can never drain a pool that woke mid-pass, and a failed
  resume is retried by the next reconcile pass. Suspend/resume serialize
  through per-pool advisory locks and propagate across HA replicas.
- **Module events.** `PoolSuspended` / `PoolResumed` manager events (reason
  `rest | query | module`) for hosted-service modules building idle policies
  on the SPI.

### CLI

- **Full REST parity, permanently enforced.** Every manager REST operation is
  now reachable from `qod` with every parameter, and a parity gate keeps it
  that way: the OpenAPI document generated from the endpoint definitions is
  committed as a contract copy (`cli/tests/resources/openapi.yaml`, pinned to
  the code by a build-time freshness check), and the CLI test suite fails
  whenever an operation or parameter lacks a command. Browser-redirect
  endpoints (OIDC / SQL-token flows) are the only exclusions, each justified
  in the test.
- **New commands from the catch-up:** `qod pool suspend`, `qod pool resume`,
  `qod pool status`, `qod catalog tags`, `qod ready`, `--start-suspended` and
  repeatable `--cohort w,r,d` on `qod pool create`, and `--tenant` on
  `qod auth mode`.

### Logging

- **Quiet by default.** One knob, `QOD_LOG_LEVEL` (env var or `-D` system
  property), now drives the whole process and defaults to `ERROR`: a plain
  `qod start` boots silently. Set `QOD_LOG_LEVEL=INFO` for the operational
  boot log (ports, module loads, reconcile) or `DEBUG` for application
  internals; the grpc/netty/Arrow firehoses stay clamped at WARN so DEBUG
  remains usable. Security misconfiguration reports (open REST API without
  `QOD_API_KEY`, dev-default `QOD_SESSION_JWT_SECRET`) are logged at ERROR so
  they survive the quiet default.

- **Operator boot banner.** The manager always prints (stdout, regardless of
  log level) the control-plane Postgres URL it is about to use, refuses to
  start with a clear message when that Postgres is unreachable, and once both
  listeners are up prints a copy-pasteable banner with the REST/UI URL, the
  FlightSQL endpoint, and ready-made JDBC, ADBC, and ODBC connection strings.

### Fixes

- The bundled demo loader script (`_load-common.sh`) is re-synced with the
  canonical copy shipped in `scripts/`.
- Malformed `--cohort` values now fail with a clean usage error instead of a
  traceback.

## 0.4.5

### Distribution

- **The native client degrades gracefully where no libquackwire is bundled.**
  On platforms with no native binary for the running JVM (Windows on ARM64:
  `quackwire.dll` is x86_64-only), the manager now logs a warning and falls
  back to the embedded HTTP client instead of crashing at JNI load. Setting
  `QOD_NATIVE_CLIENT=false` by hand on ARM Windows is no longer needed.

### Catalog

- **Restore / rollback to a snapshot (Spec 04).** `POST /api/catalog/restore` rolls a live
  table back to a prior snapshot (id or tag) as a new forward snapshot: history is preserved
  and the pre-restore state stays queryable. Dry-run returns the exact change summary that
  will be undone; `expectedCurrentSnapshot` turns a concurrent write into a 409 instead of a
  silent overwrite. Surfaced as a Restore action in the snapshot browser and the table
  history timeline, and as `qod catalog restore`. Requires an ALL grant, or DDL plus RO/RW,
  on the table. Time-travel `AT (VERSION => n)` clauses are now stripped before ACL parsing,
  so grant-holding (non-superuser) principals can run time-travel statements at all surfaces.

---

## 0.4.4

### Distribution

- **`qod` installs on Windows on ARM.** `adbc-driver-flightsql` and `pyarrow`
  ship no Windows ARM64 wheels, and their sdist builds fail, which broke
  `uvx qod` entirely on that platform. The dependencies are now skipped there
  via environment markers; the launcher, REST commands, and `qod start
  --demo` are unaffected, and `qod sql` explains the limitation instead of
  crashing.

---

## 0.4.3

### Distribution

- **Windows on ARM: DuckDB provisioning works.** The launcher now maps
  `(win32, arm64)` to DuckDB's native `windows-arm64` CLI and libduckdb
  assets; previously `qod start` on ARM Windows (e.g. Parallels on Apple
  Silicon) failed with "could not provision the duckdb CLI". Note the native
  `quackwire.dll` remains x86_64-only: set `QOD_NATIVE_CLIENT=false` for
  `qod start` on ARM Windows (the demo already forces it).

---

## 0.4.2

### Distribution

- **TLS cert auto-generation no longer requires `openssl`.** The edge now
  generates its self-signed certificate with the JRE's own `keytool` (present
  in every runtime the manager boots on), extracting the key in-process, so
  `qod start` and `qod start --demo` work on stock Windows where `openssl` is
  not on PATH. `openssl` remains as a fallback for JREs without keytool.

---

## 0.4.1

### Release tooling

- **The manager is no longer published to Maven Central.** GitHub Releases is
  the canonical jar channel (sha256-verified by the qod launcher); PyPI and
  Docker Hub are unchanged. libquackwire stays on Maven Central since it is a
  build-time dependency that must resolve anonymously.
- **libquackwire no longer version-bumps on every manager release.** Its
  version now only moves when its inputs change (the DuckDB pin or the native
  source); between changes build.sbt keeps pinning the last released coord,
  and the CI snapshot publisher fails fast if a native change lands without a
  version bump.

---

## 0.4.0

### Distribution

- **BREAKING: `qod demo` is now `qod start --demo`.** The demo is a flag on
  `start` instead of a separate subcommand; behavior is unchanged (embedded
  ephemeral Postgres, seeded TPC-H, RLS/CLS showcase, no external Postgres).
  The jar's `demo` subcommand is untouched, so `docker run ... demo` and
  `java -jar ... demo` work as before.
- **The demo banner prints when the manager is ready, not before boot.** It
  now lands at the end of the boot output with the admin UI logins and
  copy-pastable JDBC / ADBC / ODBC configurations for every seeded credential.
- **The Java runtime download no longer prompts.** When no Java 21+ is found,
  `qod start` announces the Temurin JRE download and proceeds; the now
  purposeless `--yes` / `-y` flag is removed.

---

## 0.3.10

### Distribution

- **`qod demo` / `qod start` now run the latest release by default.** The
  launcher resolves the newest GitHub release on every run instead of pinning
  the jar to the CLI's own version. When the lookup fails (offline), it falls
  back to the manager release stamped into the CLI build from `version.sbt`,
  so a cached jar still boots without network. `--version X.Y.Z` or
  `QOD_VERSION` pin a release as before.

### Release tooling

- The Discord release announce warns instead of failing when CHANGELOG.md has
  no section for the version, posting the repo and release-notes links only.
- Fixed a multi-platform Docker release race: the amd64 and arm64 builds no
  longer share one `node_modules` cache mount (concurrent `npm ci` runs could
  fail with ENOTEMPTY).

---

## 0.3.9

### Security

- **`qod demo` now serves FlightSQL over TLS.** The demo edge keeps TLS on with a
  self-signed certificate auto-generated under the ephemeral demo home (wiped on
  exit). The banner advertises `grpc+tls://` and the connect snippets carry the
  skip-verify knob (`tls_skip_verify` for ADBC, `disableCertificateVerification`
  for JDBC).

### Logging

- **Quiet by default.** Application logs now default to INFO instead of DEBUG.
  Set `QOD_LOG_LEVEL=DEBUG` (env var or `-D` system property) to get debug logs
  back when diagnosing.

### Documentation

- The self-contained demo moved to the top of the Quickstart, now with the admin
  console logins and ready-to-paste JDBC / ADBC / ODBC connection strings for
  every seeded user. The native `qod start` path is presented before Docker
  Compose.

---

## 0.3.8

### Distribution

- **One-command evaluation: `uvx qod demo`.** The Python CLI is now the launcher: it downloads
  the release jar from GitHub Releases (verified against the new `.sha256` release asset),
  auto-provisions a cached Temurin 21 JRE when no Java 21+ is present, self-installs the
  ABI-pinned DuckDB (CLI + libduckdb), bundles the node spawn scripts, and execs the jar with
  the right flags. Works the same on macOS, Linux, and Windows.
- **`qod demo` (jar subcommand).** A fully self-contained demo: embedded ephemeral Postgres,
  seeded minimal TPC-H, row/column security showcase (analyst sees masked `c_phone` and
  BUILDING-segment rows only), all state deleted on Ctrl-C.
- **`qod start` / `qod stop`.** Run a real manager against your own Postgres with no checkout,
  at env-var parity with `scripts/run-jar.sh`: `QOD_VERSION`, `JAVA_BIN`, `JAVA_OPTS`,
  `JAR_CACHE_DIR`, `DUCKDB_VERSION`, `DUCKDB_CACHE_DIR`, the `LOAD_TPCH` / `LOAD_TPCDS` /
  `LOAD_SSB` / `LOAD_TPC` background seeders, `DEMO=full|minimal`, and `NUKE=1`. `qod stop`
  discovers by port and tears down manager + nodes (SIGTERM, then SIGKILL).
- **PyPI package renamed to `qod`** (`pip install qod`); `qod-cli` remains as a compatibility
  alias. `scripts/install.sh` offers a curl-to-shell bootstrap (installs uv, then qod).

### Security

- **Row-level and column-level security are no longer experimental and are enabled by default.**
  `quack-on-demand.cls.enabled` and `quack-on-demand.rls.enabled` now default to `true`;
  `QOD_CLS_ENABLED=false` / `QOD_RLS_ENABLED=false` remain available as kill switches.
  With no policies defined the rewriters still short-circuit per statement, so deployments
  without column or row policies see no behavior change.

## 0.3.7

### Catalog

- **Per-table history / audit timeline (EPIC Spec 01).** `GET /api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}/history`
  returns the table's DuckLake commit history, newest first, with operation classification
  (create / insert / delete / update / alter / drop / maintenance / unknown), author and commit
  message (P1 stamping), a schema-changed flag, and row/file deltas attributable to the table.
  Keyset pagination (`limit` default 50 max 200, `before`) plus server-side `from` / `to` /
  `operation` / `author` filters. Identity keys on the stable DuckLake `table_id`, so history
  survives renames. New History tab on the catalog table detail page: a filterable timeline,
  a per-commit detail drawer, and deep links to "view table at this snapshot" (AS OF) and
  "compare schema" (two-snapshot diff). Null authors (pre-stamping snapshots) render as
  "unknown". History reads audit as `catalog.history.read` under the existing
  `QOD_AUDIT_CATALOG_READS` knob.
- **Time-travel viewer completed (EPIC Spec 00).** The snapshot browser now shows the author and
  commit message stamped on each snapshot (unstamped rows render "unknown") and can filter the
  timeline to one table. Tables can be viewed as of a timestamp (`asOfTs=<ISO-8601>`, resolved
  nearest-at-or-before and surfaced in the response) in addition to snapshot ids and tags. A new
  row preview (`GET .../tables/{t}/preview`) executes through the same FlightSQL pipeline as data
  traffic: pool ACLs, row-level and column-level policies all apply, reads route only to
  ReadOnly/Dual nodes, and every preview is audited. A two-snapshot schema diff
  (`GET .../tables/{t}/schema-diff?from=&to=`, ids or tag names) reports added, removed, retyped,
  and renullabled columns, rename-proof via table identity. Expired snapshots now return 410 and
  beyond-latest ids 422. The catalog browser endpoints are session-gated (closing the last
  endpoint-signature drift), with optional full read auditing via `QOD_AUDIT_CATALOG_READS`.

### Governance

- **Named and protected snapshot tags with reference pinning (EPIC P2 / Spec 06).** Any DuckLake
  snapshot can carry human-readable tags (`pre-migration`, `2026-Q2-release`) scoped per tenant-db,
  managed from the snapshot browser (chips, create modal, retention-hold toggle, pinned summary) or
  via gated REST endpoints (`POST /api/catalog/tag/create|delete|protect`,
  `GET /api/catalog/tenant/{tenant}/database/{tenantDb}/tags`). A `protected` tag pins its snapshot
  and every referenced file: the new `PinnedSetResolver` is the pin-set the managed-maintenance
  service will consult before expiring anything. The catalog table view accepts `asOfTag=<name>`
  wherever `asOf=<id>` works (both at once is a 400; unknown or dangling tags 404). Tag mutations
  are tenant-scoped, audited (`tag.create`/`tag.delete`, and dedicated `tag.hold.create`/
  `tag.hold.remove` so removing a retention hold is distinctly visible), and tag names can never be
  all digits, so they stay unambiguous next to snapshot ids.

### Security

- **ACL two-part table names are now resolved engine-consistently (attached-catalog aware).**
  Under `QOD_ACL_ENABLED=true`, `schema.table` (e.g. `tpch1.customer`) now resolves against the
  session's default catalog and just works with a matching grant; previously the static validator
  guessed catalog-first and denied it. When the first part names a catalog that may actually be
  attached on the pool's nodes (the tenant-db itself, any federation alias including disabled
  ones, or the DuckDB built-ins `memory`/`system`/`temp`), the statement is denied as ambiguous
  with an actionable message to qualify fully as `catalog.schema.table` - the engine binds such
  names catalog-first, so guessing either way would let a broad in-tenant grant reach a federated
  source without its per-alias grant. Qualification errors in general now fail closed instead of
  silently dropping the offending table from the access set. Operational note: after DELETING a
  federated source, recycle the pool - running nodes keep the catalog attached while the
  ambiguity guard no longer covers it.
- **Manager restarts no longer degrade the session schema context.** `PoolSupervisor` restore now
  preserves each tenant-db's default database/schema when rehydrating pool state, so ACL
  qualification and the RLS/CLS rewriters keep resolving unqualified names correctly after a
  redeploy instead of falling back to `main`.

### Operations

- **"Run maintenance now" works.** Manual and scheduled maintenance runs previously failed
  instantly with `flush: Permanent(java.net.ConnectException)`: the runner sent the chain's first
  statement to the ephemeral maintenance node milliseconds after fork, seconds before its DuckDB
  was listening. The spawn path now gates on TCP readiness with a bounded wait
  (`maintenance.nodeReadyTimeoutSec`, env `QOD_MAINT_NODE_READY_TIMEOUT_SEC`, default 180 to cover
  cold extension installs). The Maintenance panel also gained structured controls: a Refresh
  button on Run history, a scope select (whole database / single table with schema+table inputs),
  and per-operation checkboxes replacing the free-text CSV.
- **Single-instance profile: `DEMO=minimal`.** All three launchers (`run-jar.sh`, `run-jar.ps1`,
  `run-docker-compose.sh`) accept `DEMO=full|minimal` (default `full`). `minimal` bootstraps the
  shape for fronting a single DuckDB instance: one tenant (acme), one pool, one dual node serving
  both reads and writes, from the new bundled `bootstrap-demo-minimal.yaml` (analyst RLS/CLS demo
  included): `NUKE=1 DEMO=minimal LOAD_TPCH=1 ./scripts/run-jar.sh`. Use `full` for the
  multi-tenant, multi-pool, federation demo. The profile is only consulted when a `LOAD_*` flag is
  set and `QOD_BOOTSTRAP_YAML` is unset; bootstrap only imports into a fresh control plane, so
  switch profiles with `NUKE=1`. `DEMO=minimal` with `LOAD_TPCDS` warns and skips the loader (no
  globex tenant in this profile).
- **Managed DuckLake maintenance: auto-compaction, snapshot expiry, cleanup (EPIC Spec 09).**
  A policy-driven background service keeps every DuckLake tenant-db healthy and storage bounded:
  a leader-gated scheduler triggers runs on small-file thresholds and a per-lake cadence
  (default daily 03:00 UTC, staggered), and each run executes the full DuckLake chain (flush,
  expiry, compaction, delete-rewrite, cleanup with a grace window, orphan deletion) on an
  ephemeral dedicated node, never on serving nodes. Retention holds are enforced: expiry always
  uses explicit snapshot versions minus the P2 pin-set, and a fail-safe guard skips cleanup if a
  pinned file ever appears in the deletion schedule. Policies are hierarchical
  (table > schema > tenant-db; retention default 7 days = the time-travel horizon) via
  `POST /api/maintenance/policy/upsert`; run history is the audit trail
  (`GET /api/maintenance/runs`, `maintenance.run` audit entries with bytes reclaimed) and a
  manual `POST /api/maintenance/run` remains as an escape hatch. New Maintenance tab on the
  tenant page; `qod_maint_*` Prometheus metrics; `QOD_MAINT_*` knobs. The
  Postgres-external-catalog cleanup landmine is pinned by an integration test that proves files
  are physically deleted (and that compaction alone reclaims nothing).

### Audit

- **FlightSQL writes stamp DuckLake snapshots with the authenticated principal (EPIC P1).** Every
  INSERT / UPDATE / DELETE / DDL routed through the FlightSQL edge now sets `author =
  tenant:<tenant>/user:<user>` and `commit_message = flightsql <verb>` on the DuckLake snapshot it
  creates. The bracket is a three-PREPARE sequence on one wire connection so the exact DML row count
  is preserved. Stamping is fail-open: a prelude failure lands the write unstamped rather than
  blocking it. Knob: `quack-on-demand.stampWrites` / `QOD_STAMP_WRITES` (default on, native client
  path only). Pre-existing snapshots keep `author: null` (rendered "unknown" in the UI).

## 0.3.6

### Catalog snapshots and time travel

- **DuckLake snapshot browser with AS OF views.** The catalog browser (the standalone `/ui/catalog` page and the inline browser opened from a tenant's Databases tab) gains a per-database **Snapshots** panel: snapshot id, commit time, raw change summary, computed rows and files added or removed, and the affected tables resolved to names, newest first with keyset pagination (`limit` default 200 clamped to 1..1000, `before` cursor; "Load older snapshots" in the UI). New `GET /api/catalog/tenant/{tenant}/database/{tenantDb}/snapshots`; the table endpoint gains an optional `?asOf=<snapshot id>` that re-renders the summary, column list, and parquet files as of that snapshot, and the table detail page gets a snapshot picker, an AS OF banner, and a back-to-current action with the choice encoded in the URL. Visibility predicates match DuckDB's `AT (VERSION => n)` semantics (verified against the engine, including flushed inlined DML whose files are backdated to the logical snapshot); the AS OF row count is computed as data-file rows minus delete-file rows, so it can briefly differ from the stats-based current count on unflushed inlined rows. Unknown snapshot ids and tables that did not yet exist at the snapshot return 404. Like the rest of the catalog browser these are metadata-only Postgres reads: no Quack node round trip, and they work with zero running nodes.

### Admin UI

- **Statements/Usage chart polish and race fixes.** Recharts upgraded to v3. Statements page: a range or filter change now supersedes an in-flight load-more instead of being silently skipped, load-more pages stay inside the window the first page used, and a failed fetch clears stale charts and rows instead of leaving them under the error banner. Usage page: same stale-data clearing, no redundant fetch when switching to a custom range before both dates are picked, and the chart's aggregate bucket is labeled "(other)" so it cannot collide with a tenant named other.
- **pool/list tenant filter fixed for display names.** Tenant admins of a tenant whose display name differs from its id (the demo's own `acme` / "Acme Corporation" included) previously got an empty `/api/pool/list`, which also blanked the pool selects on the Statements and Usage pages. The filter now compares tenant ids.
- **Audit nav dropdown.** The Audit, History, and Usage tabs are grouped under a single "Audit" dropdown menu named Control Plane, Statements, and Usage; page headings follow. Paths are unchanged (`/audit`, `/history`, `/usage`).
- **Admin UI guide refreshed.** `website/docs/operating/admin-ui.md` now covers the full current UI (running-statements kill, quarantine/restart, database edit form, pool scale modal and resource limits, connections recipes, catalog table detail, RBAC scopes, config filter and manifest import flow) with regenerated screenshots.

### Usage and accounting

- **Per-tenant / per-pool / per-user metering with export.** `GET /api/usage` aggregates the daily rollup ledger over a period (defaults to the current calendar month, UTC) grouped by tenant, pool, or user; each group carries totals (`statements`, `errors`, `denied`, `engineMs`) plus per-day sub-totals. New "Usage" admin UI page: month or custom-range picker, stacked per-day bar chart (statements or engine-ms), totals table, client-side CSV export (`tenant,pool,user,statements,errors,denied,engine_ms`). Daily rollups are now retained for `QOD_USAGE_RETENTION_DAYS` (default 400) and purged by the hourly duty. Tenant admins are pinned to their tenant; the JSON API is the integration surface for external billing.

### Audit log

- **Audit filter selects and no-tenant filter.** `GET /api/audit/list` gains `noTenant=true` (superuser: only rows with no tenant scope). BREAKING for API callers: a superuser `?tenant=` filter now returns only that tenant's rows; null-tenant rows are no longer mixed in. New `GET /api/audit/actions` serves the exhaustive action vocabulary from a central registry; the Audit page's tenant and action filters are now selects (tenant options from `/api/tenant/list`, plus a "(no tenant)" option).

- **Persisted, tenant-scoped audit trail.** `QOD_TELEMETRY_STORE=postgres` (the default) records control-plane mutations, auth events (`auth.login`, `auth.login.failure`, `auth.logout`, `auth.revoke`, `auth.api-key.failure`), data-plane ACL denials (`sql.denied`), and data-plane writes (`sql.write`, `sql.ddl`) into a new `qodstate_audit` table. The action taxonomy covers all mutating handlers across tenant, database, pool, user, role, group, membership, node, federation, and manifest surfaces. Detail fields are sanitization-guaranteed at construction time: keys matching `password`, `secret`, `token`, `jwt`, or `credential` are rejected before any write reaches the store. `QOD_TELEMETRY_STORE=none` disables all recording, skips the journal fiber, purge, and rollup duties, and hides the Audit UI page. `GET /api/audit/list` (filters: family, tenant, actor, action, q, from, to, limit up to 500, keyset `before` cursor) is tenant-scoped: superusers see all rows including null-tenant ones; tenant admins see only their tenant. An hourly purge enforces `QOD_AUDIT_RETENTION_DAYS` (default 90). The `EventJournal` routes data-plane events through a bounded async queue (capacity 8192); queue overflow or append failure increments `qod_journal_dropped_total{table}` without blocking the statement hot path. A new **Audit** page in the admin UI (superusers and tenant admins; hidden when telemetry is off) surfaces a filter bar (family toggle, tenant, actor, action, time range), a newest-first table with expandable detail, and keyset load-more pagination.

### Statement history and trends

- **Persisted, tenant-scoped statement history with trend rollups.** `QOD_TELEMETRY_STORE=postgres` (the default) records every FlightSQL statement (including reads, unlike the audit log) into a new `qodstate_stmt_history` table; SQL text is capped at 500 characters. `PREPARE` probe statements are excluded: the matching `EXECUTE` row carries a `prepareMs` field instead. A background rollup job (watermark-based, idempotent, leader-gated in HA mode) aggregates raw rows into a new `qodstate_stmt_rollup` table on a configurable interval (`QOD_ROLLUP_INTERVAL_SEC`, default 300s, with a 60s skew buffer). Hourly rollups (per `tenant+pool`, `percentile_cont` p50/p95/p99, retained for `QOD_HOURLY_ROLLUP_RETENTION_DAYS` default 90) power trend charts; daily rollups (per `tenant+pool+username`, null percentiles) accumulate for the upcoming per-user usage feature whose retention knob ships with it. An outage shorter than the raw retention window (`QOD_STMT_HISTORY_RETENTION_DAYS`, default 7) recomputes cleanly on the next tick; a longer outage permanently loses the truncated rollup range. Raw retention must stay at or above 2 days because the daily recompute rebuilds the watermark's full day. `GET /api/history/statements` (filters: tenant, pool, user, status, q, from, to, limit up to 500, keyset `before` cursor) and `GET /api/history/trends` (granularity: hour or day; tenant, pool, from, to) are both tenant-scoped: superusers see everything, tenant admins see only their tenant. A new **History** page in the admin UI (superusers and tenant admins; hidden when telemetry is off) shows a range picker, three charts (statement volume, latency percentiles on hourly granularity, error rate), and a searchable statement table with keyset load-more pagination. `qod_journal_dropped_total{table="stmt_history"}` counts missed statement rows, which translate directly to undercounted rollup buckets. `QOD_TELEMETRY_STORE=none` disables all recording and hides the History page.

### High availability (opt-in, Kubernetes only)

- **Active-active manager replicas with zero-downtime rollout.** `QOD_HA_ENABLED=true` (helm: `replicaCount > 1`) runs N managers, all serving REST + FlightSQL. One replica holds a Postgres session advisory lock (`HaCoordinator`) and runs the singleton duties (reconcile respawns, bootstrap, DuckLake init, revoked-jti purge); leader duties re-run on promotion. Pool mutations serialize across replicas via per-pool advisory locks (`PoolLocker`); caches propagate via LISTEN/NOTIFY on `qod_topology` / `qod_rbac` / `qod_revocation` (published from every `PoolSupervisor` mutator and manifest import) with a periodic snapshot-refresh fallback and a deletion-aware `restore()`. JWT revocations persist in the new `qodstate_revoked_jti` table (Liquibase changelog `0014`) so a session revoked on one replica is denied on all. `/health` stays liveness-only (503 when Postgres is unreachable); new `/ready` gates readiness on Postgres. Helm wires the env flag, requires a pinned session JWT secret, sets `RollingUpdate maxUnavailable: 0`, adds a PodDisruptionBudget, and couples termination grace to `drainTimeoutSec`. HA off (default) preserves single-manager behavior; HA with the local backend is refused at config load. Documented in the RESILIENCE guide with a kind failover check.

### Security - whole-codebase audit 2026-07-02

The full report lives at `docs/security-audit-2026-07-02.md`; the findings below are fixed.

- **Node-bootstrap SQL injection closed.** `TenantDb.validate` rejects injection metacharacters in `schemaName`, `dbName`, `dataPath`, and the `pg*` connection params (denylist on `pgHost`/`pgUser`/`pgPassword`, numeric `pgPort`) at the control-plane trust boundary, covering both the local and Kubernetes backends; `spawn-quack-node.sh` also quotes SQL identifiers. Manifest import runs the injection-safety subset (`validateSafety`) so DuckLake tenant-dbs that legitimately omit keys merged from the default metastore still import.
- **ACL fails closed on parser blind spots.** `TableExtractor` now reports constructs it cannot map to a grantable table (table functions like `read_parquet`, string-literal file refs, unrecognized FROM-item / statement node types) as `unsupported` markers instead of silently dropping them, and `PostgresAclValidator` denies whenever any are present (unless the principal holds `*.*.*` ALL). Missing walker arms added (parenthesized joins, UPDATE SET-value subqueries, MERGE action subqueries, Json/Signed/Extract wrappers); CTE shadowing only suppresses unqualified names; `EXPLAIN ANALYZE <stmt>` is authorized as the inner statement it executes; `ControlFlow` is now an explicit allowlist so unknown statement types fall through to `ParseError`.
- **RLS/CLS parse failures deny.** `FlightSqlRouter` denies when `RowPolicyRewriter` / `ColumnPolicyRewriter` cannot parse a statement that has policies attached (a parse failure means filtering cannot be verified), and the transient-failure retry resends the fully rewritten (RLS-wrapped) SQL instead of the CLS-only intermediate.
- **Prepared statements peer-bound and re-authorized live.** A prepared-statement handle only executes for the peer that created it (a leaked handle replayed from another connection gets UNAUTHORIZED), and both Execute paths re-read the `EffectiveSet` from the live session at call time instead of replaying the Prepare-time snapshot - a grant revoked mid-session now takes effect on prepared statements exactly as it does on one-shot statements, and a handle whose session has expired resolves to a deny.
- **Cross-tenant RBAC privilege escalation closed.** `addUserRole` / `addUserGroup` / `addGroupRole` enforce that the referenced role/group shares the principal's tenant.
- **Cookie-authenticated sessions are now tenant/superuser-scoped on all
  REST endpoints.** Body-tenant, id-only, and read/superuser-gated endpoints
  previously enforced scope only for the X-API-Key header path; a browser
  session (HttpOnly qod_session cookie, no header) reached the handlers with
  no resolved identity and was admitted. This let a tenant admin mutate other
  tenants' pools, databases, and RBAC objects, reach superuser-only operations
  (manifest export/import, server config), and receive unfiltered cross-tenant
  reads from pool/tenant list and statement history. The worst case was
  manifest import: a cookie-authenticated tenant admin could overwrite the
  entire control plane. All such endpoints now resolve the session cookie into
  the scope check (header still takes precedence). Path/query-tenant endpoints
  were already covered by the perimeter guard.
- **Constant-time API-key compare.** `apiKeyGuard` compared `X-API-Key` with `String.equals`, leaking the key length-by-length via response timing; replaced with `MessageDigest.isEqual`.
- **Internal exception text no longer reaches Flight clients.** Every catch-all INTERNAL arm in `FlightProducerImpl` now logs the full detail server-side against a short random errorId and returns only `internal error (errorId=...)`; raw messages could carry SQL fragments, hostnames, and file paths. Curated `RouterFailure` messages still flow through unchanged.
- **K8s spawn-failure orphans cleaned up.** `start()` deletes the pod, service, and per-pod token Secret when a spawn fails partway, preventing orphan accumulation and a deterministic-nodeId respawn deadlock.

### Observability

- **DuckDB engine metrics per node.** The manager previously collected nothing from inside the DuckDB engine - every per-node signal was derived from wire round-trip latency, so memory pressure and spill-to-disk were invisible until they degraded latency. A new `EngineStats` scrape runs a single round-trip over `duckdb_memory()` and `duckdb_temporary_files()` through the existing `quack_query` wire (no node-side change), piggybacked on every successful health-probe tick; a failed scrape keeps the previous sample and can never flip a node unhealthy. Surfaced in three places: four Prometheus gauges tagged `(tenant, pool, node_id, role)` (`node_duckdb_memory_used_bytes`, `node_duckdb_temp_storage_bytes`, `node_duckdb_spill_files`, `node_duckdb_spill_bytes` - nodes never scraped publish no row rather than a false zero), new Mem / Spill columns plus fleet-total cards on the admin UI's Nodes page (`NodeInfo` gains the four fields), and a "DuckDB Engine" row in the bundled Grafana dashboard. Verified live against a TPC-H load: the buffer-manager gauge tracks query activity and REST, `/metrics`, and the UI agree. Two live-run fixes are folded in: the quack wire's schema-only first Arrow batch is skipped when decoding the scrape result, and `NodeInfo`'s hand-rolled circe codec now carries the new fields (a `NodeInfoCodecSpec` round-trip pins the shape so an added field can no longer silently vanish from `/api/pool/list`).

### Incident response

- **Incident response for administrators:** durable node quarantine/unquarantine (survives restarts, HA-propagated, probe-proof), superuser node restart via the reconcile respawn path, live in-flight statement view on the Nodes page, and best-effort statement kill (stream disconnect locally, qod_kill NOTIFY fan-out across HA replicas). New endpoints: POST /api/node/unquarantine, POST /api/node/restart, GET /api/node/active-statements, POST /api/statement/kill.

### Demo, scripts, and release

- **Releases are announced on Discord #news.** `release-jar.sh` posts the repo link plus the version's changelog section (chunked to Discord's message limit) via the new `scripts/announce-release-discord.sh` right after creating the GitHub release; webhook from `QOD_DISCORD_WEBHOOK_URL` or the untracked `.env`, failures warn without failing the release.
- **SSB star schema seeded via `LOAD_SSB=N`** (bundled into the `LOAD_TPC` shortcut next to TPC-H and TPC-DS). DuckDB has no ssb extension, so `scripts/load-ssb-dbgen.sh` generates TPC-H in-memory and derives the 5 SSB tables (`lineorder`, `customer`, `supplier`, `part`, `dwdate`) per the O'Neil spec mapping; the date dimension is named `dwdate` so the 13 canonical queries run unquoted. Lands in schema `ssb1` of the existing `acme_tpch` tenant-db, wired into all three install paths.
- **kind local stack unbroken + loaders no longer OOM at SF >= 10.** The helm step died with `HELM_EXTRA_ARGS[@]: unbound variable` on bash 3.2 when no TPC seed was requested; the TPC loaders were OOM-killed at SF >= 10 because DuckDB's default memory budget ignores the cgroup limit - a cgroup-aware `memory_limit` (default 40% of the cgroup cap, `MEMORY_LIMIT`-overridable) makes it spill to disk instead.
- **Compose summary crash fixed.** The end-of-script summary still read the removed `_profile_set` array, so under `set -u` every `run-docker-compose.sh` run died after the stack was already up, losing the URL summary and the zero exit code.
- **Release: tag pushed before `gh release create`** (which requires the tag on the remote).

### Kubernetes node pods

- **Kubernetes node-pod sizing and templates.** Pools carry `cpu` and `memory`
  (each applied as request and limit on the quack container, Guaranteed QoS
  when both set) via `POST /api/pool/setResources` and sliders on the pool
  create form and pool detail page (CPU 0.5-128 cores, memory 1-1024 Gi).
  Behind `QOD_POD_TEMPLATE_ENABLED` (default off, superuser only), a pool may
  carry a full Pod-manifest template through `POST /api/pool/setPodTemplate`
  (API-only); the manager overlays the pod identity labels, the quack
  container's env contract, and resources onto it, so sidecars/volumes/affinity
  are expressible while adoption and routing keep working. Applies on next node
  spawn. The local backend ignores these (Kubernetes-only).
- **Manifest round-trips pod sizing and templates.** Manifest export/import now carries per-pool `cpu`, `memory`, and `podTemplateYaml`; previously they were dropped, so a backup/restore silently lost pod sizing and the pod template. Verbatim round-trip (non-secret operator config); the manifest is superuser-only.

### Database configuration

- **Per-database init SQL.** Tenant-dbs carry an `initSql` executed at node boot
  before the quack extension loads, ahead of the pool's own initSql and the
  federation blob, so operators set temp directory, memory limit, or extension
  loads once per database and pools can still override. Field on database create,
  the manifest, and the Databases UI.
  New endpoint POST /api/database/update edits any safe subset of a database
  (metastore, object store, default database/schema, init SQL; name, kind, and
  data path stay immutable) and restarts all the database's nodes when
  node-affecting fields change. The Databases UI opens the editor on name
  click, shows per-database table counts linking into the catalog browser,
  and reports the effective data path for databases inheriting the default.

## 0.3.5

### Connectivity and auth

- **Browser OAuth token page for JDBC / DBeaver.** New guard-exempt `/api/auth/sql-token` start + callback flow on the manager port: the browser is redirected to the edge OIDC provider (endpoints resolved via OIDC discovery, so split-horizon deployments work), logs in, and the callback renders the id token (aud = the edge client id) for pasting into DBeaver's token property. Retires the dormant standalone `:8888` OAuth broker - `OAuthHttpServer`, the `auth.oauth{}` config block, and the `QOD_AUTH_OAUTH_*` env vars are gone; a single `auth.oauthScopes` key remains. State token uses a constant-time HMAC compare. The kind rig's Keycloak realm enables the auth-code flow and derives the token's tenant claim from a per-user attribute instead of hardcoding one.
- **Multi-FETCH Arrow streams no longer corrupt.** `ChainedQuackArrowReader` swapped to a fresh child root on every FETCH round while the Flight stream kept flushing the first (closed) root, so any result spanning more than one PREPARE/FETCH round-trip surfaced client-side as `mismatch number of rows in column: got=0, want=N`. The reader now owns one stable `VectorSchemaRoot` and copies each child batch into it, restoring the ArrowReader contract.

### Pools and supervision

- **Drain / force stop scales the pool to 0 instead of deleting it.** The pool row survives and stays drained across manager restarts. Deletion is now a dedicated path: `POST /api/pool/delete` plus a Delete button in the UI.
- **Periodic reconcile.** `reconcile()` runs on a background fiber every `reconcileIntervalSec` (default 30s, env `QOD_RECONCILE_INTERVAL_SEC`, `0` restores boot-only), so a node that dies while the manager is up is respawned on the next tick. Also fixes a node stuck in "draining" after drain + rescale: the stale `NodeLoadTracker` entry is reset before respawn and removed on drain/delete.

### Examples and docs

- **FlightSQL client examples in four languages** under `examples/`: TypeScript (raw gRPC + apache-arrow, since Node has no first-party driver), Python (ADBC), Java (Flight SQL JDBC), and Rust (arrow-flight over tonic). Each runs single queries and the 22 standard TPC-H queries against a live edge, sharing the `QOD_*` env-var contract. An n8n community node (catalog operations via FlightSQL) was added and then moved to its own repo, as was the Power BI connector.
- **Administration docs section**: onboarding golden paths, access-control, day-2 operations, lifecycle and config playbooks, an overview + task map, and a "Manage by manifest" page with YAML fragments aligned to each playbook. Corrections along the way: state lives in `qodstate_*` tables, the removed `/api/acl/grant` API replaced with real RBAC calls, required `tenantDb` added to pool curls.
- **Connecting guides** for DBeaver and Tableau; README repositioned around the DuckLake serving layer with an honest comparison table and a data-residency diagram; `.env.example` clarifies public vs internal ports.

### Build and release

- **`release.sh` split into resumable per-artifact phases**: a shared `release-lib.sh` plus `release-libquackwire.sh` (native jars to Maven Central), `release-jar.sh` (manager jar, tag, GitHub release), and `release-docker.sh` (multi-arch image), each idempotent so a mid-release failure is resumed by re-running the failed phase.
- **libquackwire CI publishes on manual `workflow_dispatch`** from main, enabling a snapshot re-publish without a code push.
- `run-jar.sh` keeps sbt/JLine from leaving the WSL pty in raw mode; the UI pool Delete button shows only the icon.

## 0.3.4

- **DuckDB upgraded 1.5.3 -> 1.5.4** across every pinned layer: the libquackwire native shim rebuilt against libduckdb 1.5.4 (`1.5.4-40de7badae41-1`, duckdb-quack submodule at the v1.5-variegata tip), the DuckDB JDBC driver (`1.5.4.0`), and the runtime libduckdb / DuckDB CLI fetched by the Dockerfiles and `run-jar.sh`.

## 0.3.3

### Security

- **Row-level security.** Per-role row policies - a boolean SQL predicate authored via REST, the admin UI's Role "Row policy" tab, or a YAML manifest - are enforced on every SELECT before it reaches a Quack node. The gateway rewrites the query to fold the policy predicate into the statement's WHERE so a role only ever sees the rows its predicate admits; superusers bypass, matching the table- and column-level ACLs. New `qodstate_role_row_policy` table (Liquibase changelog `0013`), `RoleRowPolicy` model, an `EffectiveSet` row-policy field, REST + effective-set + manifest plumbing, a demo policy seeded on `acme/analyst`, and an `adbc.sh` query helper to exercise it. Documented in the rbac-model guide.

### Admin-UI authentication (OIDC SSO)

- **OIDC single sign-on for the admin UI.** Provider-agnostic OpenID Connect discovery (Keycloak, Google, Azure, AWS) drives an authorize / callback / end-session flow protected by a signed state token and PKCE. New `OidcSsoService` plus `AuthHandlers.oidcStart/oidcCallback/oidcLogout` (system scope, superuser-only); `auth.management.publicBaseUrl` builds the redirect URIs; `/api/config/client` surfaces `identitySource` + `ssoProviderName` so the UI shows a pure-SSO login branch when `identitySource=oidc`. Open-redirect guard and PKCE-verifier round-trip are test-covered. The Helm chart gains admin-UI OIDC SSO config and a local-stack demo.
- **Per-tenant admin-UI login mode.** `GET /api/auth/mode` resolves the login mode (local password vs OIDC SSO) per tenant via `ManagementAuthModeResolver`; the UI queries it on the login screen so different tenants can present different flows. `mintSessionFor` keys grant derivation on the resolved per-scope mode, and `identitySource` is now a system-scope-only concept.
- **Keycloak split-horizon bearer validation.** A Keycloak issuer override lets a bearer minted against an internal issuer URL validate when discovery advertises a public one. FlightSQL identity now maps from `preferred_username` (not `sub`), and `x-qod-superuser` is accepted as an alias of the superuser flag.

### FlightSQL edge - Power BI / ODBC compatibility

- **Apache Arrow Flight SQL ODBC driver compatibility (Power BI).** Conformance fixes for the Arrow Flight SQL ODBC driver: `GetSchema` for query commands, `parameter_schema` set on Prepare results (empty = 0 params), `FlightSqlServerTransaction=NONE` instead of `TRANSACTION`, R7/R12 conformance, per-RPC auth on `DoGet`, `x-qod-authorization` accepted as an alias for `Authorization`, and `LIKE` (not `=`) for the catalog filter in `getStreamSchemas` / `getStreamTables`. Adds a Power BI connector install/connect guide, the signed `.pqx` + self-signed cert path, and per-provider OAuth (Azure / AWS / Google) docs.
- **Literal DML / DDL over FlightSQL.** `INSERT` / `UPDATE` / `DELETE` and DDL statements execute over the Flight SQL update path; the result advertises a `Count` schema so ADBC accepts the stream.

### Features

- **Per-pool `initSql`.** SQL set on a pool is prepended to the federation blob at node spawn (Liquibase changelog `0011`), so every node in the pool runs operator-supplied setup before serving queries.
- **Remote-access local stack.** `PUBLIC_HOST`, a NodePort on `31338`, and Power BI OAuth wiring let the kind-based local stack be reached from off-box clients.

### Fixes and internals

- **Tenant slug as the single key.** A tenant's slug id is now the one identity key, decoupled from its display name; fixtures and specs realigned.
- **Caches moved to Caffeine.** The RBAC effective-set cache and `GoogleGroupsLookup.groupsCache` migrated from `ConcurrentHashMap` to bounded Caffeine caches.
- **UI clears the prior tenant's data on tenant switch** in the Users screen.
- **`run-docker-compose.sh` made bash 3.2 compatible**; the truncated `scopes` value in the Helm `values-local-stack.yaml` fixed.
- **Release hardening.** `scripts/release.sh` now purges stray Metals Scala-CLI scratch dirs before building (a Scala 3.8 TASTy left under the source tree broke `Compile / doc` during `publishSigned`) and builds the Docker image from the `v<version>` tag rather than the post-release `-SNAPSHOT` working tree.
- Node distribution display uses the user name instead of the node position.

## 0.3.2

### Security

- **RBAC tenant scope on every handler.** Every endpoint in `/api/{user,role,group,membership,pool/permission}/*` now checks the calling session's `manageableTenants` before mutating. Tenant-A admin sessions get `403 tenant_forbidden` on tenant-B resources; missing ids return `404` (no cross-tenant existence leak).
- **Statement-history cross-tenant leak fixed.** `GET /api/node/statements` filters the ring buffer by the session's manageable tenants. Allow-set covers both surrogate id and display-name forms so the filter survives the FlightSQL router recording in either shape.
- **JWT session cookies.** `SessionTokenStore` is now a stateless HS256 JWT signer/verifier; login also sets `qod_session=<jwt>` as `HttpOnly Secure SameSite=Lax`. Sessions survive manager restart when `QOD_SESSION_JWT_SECRET` is pinned, enabling horizontal scale-out. The UI drops the localStorage token path entirely - browsers use the cookie; CLI clients still use `X-API-Key`.
- **Cloud secret resolvers gated.** `secretStore = aws-sm | gcp-sm | azure-kv | vault` is refused at config load (resolvers are stubs). Under `dispatch` mode the stubs stay wired so postgres+env deployments work; the runtime error names the prefix and the supported alternatives. UI dropdown options for stub stores are disabled.
- **FederationBlobBuilder SQL-quote-safe.** Every resolved secret value and the expanded alias are doubled (`'` → `''`) before splicing into the operator template. Apostrophes in passwords no longer break the `ATTACH`.
- **DuckLake ATTACH escape hardened.** Postgres calls in `DuckLakeInitializer` switched to `PreparedStatement` binds; the DuckLake connstring uses a two-layer escape (`libpqValue` inside `duckdbLiteral`) so the bearer password can contain any character.
- **Full 128-bit ids.** Every surrogate id is now `<prefix>-<32 hex>` (`Names.newSurrogateId`) instead of `<prefix>-<8 hex>` (~77K-row collision). Legacy 8-char ids in existing rows continue to match.
- **K8s federation works.** Per-pool `qod-fedsql-${tenant}-${tenantDb}-${pool}` Secret carries `extraSetupSql`; pods reference it via `env.valueFrom.secretKeyRef`. `kubectl describe pod` never shows the credential; etcd doesn't either.
- **K8s node tokens persist across restart.** Per-pod `qod-token-${nodeId}` Secret carries the bearer; `discoverExisting` reads it back, so adopted pods don't 401 on manager restart.
- **REST stubs removed.** `POST /api/node/setRole` and `POST /api/node/restart` were stubs that lied about persisting state. Use `POST /api/pool/scale` or `POST /api/node/quarantine` instead.
- **K8s ServiceAccount can manage Secrets.** The chart's `Role` was granting `pods` + `services` but omitting `secrets`, so `KubernetesQuackBackend.ensureTokenSecret` (mandatory pre-pod step) failed with `403 forbidden` and the manager `CrashLoopBackOff`-ed on every fresh helm install.
- **`whoami` error codes differentiated.** A failed lookup used to surface as `{"error":"expired"}` for six different conditions (no token, malformed JWT, signature mismatch from a rotated secret, missing jti, exp past, jti revoked). New `SessionTokenStore.LookupResult` ADT maps each cause to a distinct response code (`no_session` / `invalid` / `expired` / `revoked`), so operators reading the network tab can tell "secret rotated on helm upgrade" apart from "session timed out".
- **Cookie `Secure` flag auto-derives from request scheme.** `sessionCookieSecure` defaults to `auto` (was `true`): the handler reads `X-Forwarded-Proto` per request - `https` → `Secure`, `http`/absent → not `Secure`. `run-jar.sh` on plaintext HTTP no longer drops the `Set-Cookie` response, while helm behind a TLS-terminating ingress keeps `Secure=true` automatically. `QOD_SESSION_COOKIE_SECURE=true|false` still forces either value for misconfigured proxies that strip the header.
- **Column-level security.** Per-role, per-column policies (action: `deny` or `mask`) authored via REST, admin UI, or YAML manifest. The gateway rewrites SELECTs before they reach a Quack node: every covered column reference (in projection, WHERE, HAVING, subqueries, CTE bodies, UNION arms) is replaced with the operator-authored transform expression; `SELECT *` is expanded against the DuckLake column catalog and masked in place; a `deny` match short-circuits the query with a 403-style error. Transform SQL is strict-containment-validated at write time (must be one scalar expression referencing only the protected column; subqueries, denylisted functions like `read_parquet`, `attach`, `pragma_*`, and references to other columns are rejected). Superusers bypass, matching the existing table-level ACL. New `qodstate_role_column_policy` table; new `EffectiveSet.columnPolicies` field; three Micrometer metrics (`column_policy_rewrites_total`, `column_policy_catalog_lookups_total`, `column_policy_rewrite_duration_seconds`); ~25 new tests across `TransformSqlValidatorSpec`, `ColumnPolicyRewriterSpec`, `RoleColumnPolicyStoreSpec`, `RoleColumnPolicyHandlersSpec`, `ManifestColumnPolicyRoundTripSpec`; new admin UI tab "Column policies" on the Role detail page; manifest YAML round-trip with example policy seeded in `bootstrap-demo.yaml`.

### Performance

- **HikariCP on the control-plane store.** `PostgresControlPlaneStore` and `UserStore` use HikariCP (sizes 20 and 10). Handshake path went from 5 fresh TCP+TLS+auth handshakes per request to 5 borrow-from-idle.
- **EffectiveSet cache.** 60s TTL `ConcurrentHashMap` keyed by `(userId, jwtRolesHash, jwtGroupsHash)`; invalidated from every RBAC mutator + `restore()`. Collapses N FlightSQL handshakes for the same user into 1.
- **Manifest export/import.** Single `store.snapshot()` + name→entity index maps replaced N+1 list calls per tenant/user/role.
- **`unsafeRunSync` in `PoolSupervisor.createPool`** removed (proper `flatMap` composition).
- **K8s `waitReady`** switched from a `Thread.sleep(500)` busy-poll to fabric8's `waitUntilCondition` watch - ~120 fewer API-server calls per 60s pod startup.
- **`DuckLakeCatalogReader` evicted** on tenant-db delete: the per-tenant-db Hikari pool is closed promptly. Stale-credential foot-gun closed.
- **Sessions auto-expire.** Initial sliding-window idle TTL on `SessionTokenStore` (superseded by the stateless JWT exp).
- **FlightSQL Prepare cheaper + co-located with Execute.** ADBC `cur.execute()` was hitting the backend twice (Prepare ran the full SQL to read the result schema, Execute ran it again to stream rows), routed independently so the two halves could land on different nodes. Prepare now classifies the SQL through `PrepareStrategy`: DML/DDL skip the node entirely (also fixing a latent double-INSERT hazard), wrappable SELECTs send a `LIMIT-0` subquery probe whose schema feeds `dataset_schema` in milliseconds, and the rest fall back to today's path. The Execute then soft-pins to the node that served Prepare so DuckDB's per-process caches stay warm.
- **Prepare + Execute merged into one UI history row.** The `LIMIT-0` probe no longer records a separate row; the matching Execute carries the probe duration as `prepareDurationMs` and the UI renders it as subtext (`57 ms / prep 28 ms`) under the Execute duration.

### Demo, loaders, and bare-jar UX

- **`LOAD_TPC=N` split into `LOAD_TPCH=N` + `LOAD_TPCDS=N`.** Each benchmark is now opt-in at its own scale factor across `run-jar.sh`, `run-docker-compose.sh`, and the kind-based `run-local-stack-k8s.sh`. `LOAD_TPC=N` stays as a legacy shortcut for both (explicit per-bench vars override it). The Compose + k8s launchers also stop pre-creating Postgres databases that won't be seeded. Fixes a real k8s bug where the chart README already advertised `LOAD_TPCH=1` but the launcher script only honored `LOAD_TPC`, so users following the README got nothing seeded.
- **TPC-DS workload in the bundled load tester.** `tpch-load-test.py` gains `--workload tpcds` (or `LT_WORKLOAD=tpcds`); the runner cycles 7 curated queries (Q3, Q7, Q19, Q42, Q52, Q55, Q98) covering per-group aggregation, multi-way joins, top-N, and a window function. Default schema auto-picks `tpcds1` to match `scripts/load-tpcds-dbgen.sh`. The existing TPC-H mix stays the default.
- **DuckDB CLI + `libduckdb` self-installed by `run-jar.sh`.** First boot fetches both into `$REPO_DIR/.duckdb/<version>/{bin,lib}` at the ABI libquackwire links against (pinned via `DUCKDB_VERSION`, relocatable via `DUCKDB_CACHE_DIR`), prepends them to `$PATH` and `LD_LIBRARY_PATH` / `DYLD_LIBRARY_PATH`, and skips re-downloads on subsequent runs. Mandatory by design: a system duckdb at the wrong ABI crashes the first node spawn with a confusing `UnsatisfiedLinkError`. Air-gapped operators pre-populate the cache directory and the fast path takes over.
- **Loader temp-spill fixed for SF >= 10.** `load-tpch-dbgen.sh` and `load-tpcds-dbgen.sh` now anchor `temp_directory` to an absolute `.tmp/duckdb-{tpch,tpcds}-load/` they pre-create, so DuckDB's spill-to-disk during `dbgen` / `dsdgen` actually lands somewhere writable. Previously the call aborted with `IO Error: Cannot open file ".tmp/duckdb_temp_storage_DEFAULT-1.tmp"` and every subsequent `CREATE TABLE ... AS SELECT FROM memory.main.<t>` failed because the source tables were never populated.
- **Release script attaches a GitHub Release.** `scripts/release.sh` now calls `gh release create v$version --generate-notes` against the tag sbt-release pushed, so the assembly jar lands on the GitHub Releases page alongside the Maven Central artifact.
- **Smoke test rewritten for the live demo bootstrap.** `test-api.sh` was creating a pool under tenant `tpch` (which never existed in the demo bootstrap) and missing the `tenantDb` field added when the pool key became `(tenant, tenantDb, pool)`. It now creates a `smoke` pool under `acme/acme_tpch` (cleaned up at the end), and tolerates 409 on tenant + pool creation so re-runs are safe.

### Cleanup

- **File-state mode dropped** (~250 LOC + ~80 LOC tests): `PostgresStateStore`, `StateStore`/`FileStateStore`, `StoredState`, `StoredPool`, `StoredTenant`. The `stateStorage` / `statePath` config knobs and all 5 `Main.scala` guards are gone. Postgres is the only control-plane store.
- **`ManifestIdentity` + `FederationImportSummary`** dropped (defined, never read).
- **`DbAdmin.dropDatabase` non-FORCE fallback** dropped (PG16 floor - always supported).

## 0.3.1

- Per-pool node placement (cohorts) - a pool can now be defined as a
  list of cohorts. Operators can express
  layouts such as "2 writers on nodes tagged `disktype=ssd` and 1 reader
  + 1 writer on nodes tagged `disktype=hdd`" against a single pool.
- JDK floor raised to 21.