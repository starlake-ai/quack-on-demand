// Engine tests for the per-user S3 credentials (decision 23) on a real DuckDB instance: qod_session
// installs the principal's credentials, fetched from the replica its handle names (the test server), as
// connection secrets qod_uc_<id8> with every key set; replaces them when the digest changes; drops them
// for an empty one; makes them again after a client ROLLBACK, an earlier version restored under the same
// name included; and refuses every statement on a connection whose fetch failed, was for another
// digest or for the unavailable one, until a prelude syncs. A connection stands in for a quack client
// connection by carrying quack's session tag. Built with -DQOD_NODE_TESTS=ON, run by CTest.

#include "authz.hpp"
#include "credentials.hpp"
#include "duckdb.hpp"
#include "duckdb/catalog/catalog_transaction.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/secret/secret.hpp"
#include "duckdb/main/secret/secret_manager.hpp"
#include "node_hooks.hpp"
#include "sql_script.hpp"
#include "test_credentials.hpp"
#include "test_http_server.hpp"

#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

using qod_test::CredentialEntry;
using qod_test::Digest;
using qod_test::kKey;
using qod_test::kNode;
using qod_test::MintFrom;

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

int64_t now_ms = 1761200000000;

//! What the test replica answers.
struct Replica {
	std::mutex lock;
	int status = 200;
	std::string body;
};
Replica replica;

void Serve(const std::string &creds, const std::vector<CredentialEntry> &entries,
           const std::string &key = kKey) {
	std::lock_guard<std::mutex> guard(replica.lock);
	replica.status = 200;
	replica.body = qod_test::SignCredentials(qod_test::CredentialsJson(creds, entries), key);
}

void Fail(int status, const std::string &body) {
	std::lock_guard<std::mutex> guard(replica.lock);
	replica.status = status;
	replica.body = body;
}

qod_test::HttpAnswer Answer(const qod_test::HttpRequest &) {
	std::lock_guard<std::mutex> guard(replica.lock);
	qod_test::HttpAnswer answer;
	answer.status = replica.status;
	answer.body = replica.body;
	return answer;
}

struct FakeQuackSession : public duckdb::ClientContextState {};

void TagAsQuackSession(duckdb::Connection &con) {
	con.context->registered_state->Insert(qod::kQuackSessionKey, duckdb::make_shared_ptr<FakeQuackSession>());
}

//! The manager's prelude for `handle`, then `sql`, as a node connection receives them: the first
//! error, or empty.
std::string Run(duckdb::Connection &con, const std::string &handle, const std::string &sql = "SELECT 1") {
	auto prelude = con.Query(qod_test::Prelude(handle));
	if (prelude->HasError()) {
		return prelude->GetError();
	}
	auto result = con.Query(sql);
	return result->HasError() ? result->GetError() : "";
}

//! Like Run, each statement prepared then executed, as quack sends them.
std::string RunPrepared(duckdb::Connection &con, const std::string &handle, const std::string &sql = "SELECT 1") {
	for (auto &text : {qod_test::Prelude(handle), sql}) {
		auto prepared = con.Prepare(text);
		if (prepared->HasError()) {
			return prepared->GetError();
		}
		duckdb::vector<duckdb::Value> none;
		auto result = prepared->Execute(none);
		if (result->HasError()) {
			return result->GetError();
		}
	}
	return "";
}

//! The name of the secret DuckDB would sign `url` with on `con` (what which_secret() answers), or
//! "<none>". Read through the secret manager: which_secret() and duckdb_secrets() are superuser
//! functions, which a user's statement may not call once Task 10 lands.
std::string Signer(duckdb::Connection &con, const std::string &url) {
	std::string name = "<none>";
	con.context->RunFunctionInTransaction([&] {
		auto match = duckdb::SecretManager::Get(*con.context)
		                 .LookupSecret(duckdb::CatalogTransaction::GetSystemCatalogTransaction(*con.context), url, "s3");
		if (match.HasMatch()) {
			name = match.GetSecret().GetName().GetIdentifierName();
		}
	});
	return name;
}

//! The connection secret `name` of `con` as duckdb_secrets() shows it (redacted), or "<none>".
std::string Redacted(duckdb::Connection &con, const std::string &name) {
	std::string text = "<none>";
	con.context->RunFunctionInTransaction([&] {
		auto entry = duckdb::SecretManager::Get(*con.context)
		                 .GetSecretByName(duckdb::CatalogTransaction::GetSystemCatalogTransaction(*con.context), name,
		                                  duckdb::SecretManager::CONNECTION_STORAGE_NAME);
		if (entry) {
			text = entry->secret->ToString(duckdb::SecretDisplayType::REDACTED);
		}
	});
	return text;
}

//! The connection's user credential secrets by name, each as "<storage> <type> <scope> " then every key
//! the node sets, in a fixed order: what httpfs would read for the connection.
std::map<std::string, std::string> UserSecrets(duckdb::Connection &con) {
	std::map<std::string, std::string> found;
	con.context->RunFunctionInTransaction([&] {
		auto &manager = duckdb::SecretManager::Get(*con.context);
		for (auto &entry : manager.AllSecrets(duckdb::CatalogTransaction::GetSystemCatalogTransaction(*con.context))) {
			auto &name = entry.secret->GetName().GetIdentifierName();
			if (name.rfind(qod::kUserSecretPrefix, 0) != 0) {
				continue;
			}
			auto &secret = entry.secret->Cast<duckdb::KeyValueSecret>();
			auto text = entry.storage_mode + " " + secret.GetType().GetIdentifierName() + " ";
			for (auto &scope : secret.GetScope()) {
				text += scope + " ";
			}
			for (auto key : {"key_id", "secret", "session_token", "region", "endpoint", "url_style", "use_ssl",
			                 "kms_key_id", "url_compatibility_mode", "requester_pays"}) {
				duckdb::Value value;
				text += std::string(key) + "=" +
				        (secret.TryGetValue(duckdb::Identifier(key), value) ? value.ToString() : "<missing>") + ";";
			}
			found[name] = text;
		}
	});
	return found;
}

CredentialEntry Entry(char digit, const std::string &scope, const std::string &key_id, const std::string &secret) {
	CredentialEntry entry;
	entry.id = std::string(8, digit) + std::string(24, '0');
	entry.scope = scope;
	entry.key_id = key_id;
	entry.secret = secret;
	return entry;
}

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(kNode, kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz);
	// Stand in for httpfs's s3 secret type, so the test needs no extension (on a node, RegisterSecret
	// autoloads httpfs, installed at init, for the type).
	duckdb::SecretType s3;
	s3.name = "s3";
	s3.deserializer = duckdb::KeyValueSecret::Deserialize<duckdb::KeyValueSecret>;
	s3.default_provider = "config";
	duckdb::SecretManager::Get(*db.instance).RegisterSecretType(s3);
	qod_test::TestHttpServer server(Answer);
	auto url = server.Url();
	auto exp = now_ms + 60000;

	// ---- a user's credentials become their connection's secrets, every key set
	auto a = Entry('a', "s3://lake/alice/", "AKIAALICE", "alice-secret");
	auto d1 = Digest('1');
	Serve(d1, {a});
	duckdb::Connection alice(db);
	TagAsQuackSession(alice);
	Expect(Run(alice, MintFrom(url, "u-alice", d1, exp)).empty(), "alice's statement runs");
	auto secrets = UserSecrets(alice);
	Expect(secrets.size() == 1 &&
	           secrets["qod_uc_aaaaaaaa"] ==
	               "connection s3 s3://lake/alice/ key_id=AKIAALICE;secret=alice-secret;session_token=;"
	               "region=us-east-1;endpoint=;url_style=vhost;use_ssl=true;kms_key_id=;"
	               "url_compatibility_mode=false;requester_pays=false;",
	       "one connection secret qod_uc_aaaaaaaa, scoped to the credential, every key explicit");
	Expect(server.Requests() == 1 && server.Last().path == "/api/internal/authz/credentials" &&
	           server.Last().headers["x-qod-node"] == kNode,
	       "fetched once from the handle's replica");
	Expect(Signer(alice, "s3://lake/alice/x.parquet") == "qod_uc_aaaaaaaa", "alice's URLs are signed with her secret");
	auto listed = Redacted(alice, "qod_uc_aaaaaaaa");
	Expect(listed.find("AKIAALICE") != std::string::npos && listed.find("alice-secret") == std::string::npos,
	       "duckdb_secrets() redacts the secret");
	Expect(Run(alice, MintFrom(url, "u-alice", d1, exp)).empty() && server.Requests() == 1,
	       "the same digest is not fetched again");

	// ---- another connection of alice is served from the node's cache; bob's holds none
	duckdb::Connection alice2(db);
	TagAsQuackSession(alice2);
	Expect(Run(alice2, MintFrom(url, "u-alice", d1, exp)).empty() && UserSecrets(alice2).size() == 1 &&
	           server.Requests() == 1,
	       "alice's second connection gets her secret from the cache");
	duckdb::Connection bob(db);
	TagAsQuackSession(bob);
	Expect(Run(bob, MintFrom(url, "u-bob", "", exp)).empty() && UserSecrets(bob).empty(), "bob holds no secret");
	Expect(Signer(bob, "s3://lake/alice/x.parquet") == "<none>",
	       "alice's secret is not bob's: connection secrets are the connection's own");
	Expect(server.Requests() == 1, "an empty digest fetches nothing");

	// ---- a replaced and an added credential: a new digest
	auto a2 = a;
	a2.secret = "alice-secret-2";
	a2.session_token = "alice-token";
	a2.region = "eu-west-3";
	a2.endpoint = "minio.internal:9000";
	a2.use_ssl = false;
	auto b = Entry('b', "s3://lake/shared/", "AKIAALICE2", "alice-secret-3");
	b.url_style = "vhost";
	auto d2 = Digest('2');
	Serve(d2, {a2, b});
	Expect(Run(alice, MintFrom(url, "u-alice", d2, exp)).empty() && server.Requests() == 2, "a new digest is fetched");
	secrets = UserSecrets(alice);
	Expect(secrets.size() == 2 &&
	           secrets["qod_uc_aaaaaaaa"] ==
	               "connection s3 s3://lake/alice/ key_id=AKIAALICE;secret=alice-secret-2;session_token=alice-token;"
	               "region=eu-west-3;endpoint=minio.internal:9000;url_style=path;use_ssl=false;kms_key_id=;"
	               "url_compatibility_mode=false;requester_pays=false;" &&
	           secrets["qod_uc_bbbbbbbb"].find("url_style=vhost;") != std::string::npos,
	       "the replaced credential's secret is replaced, the added one installed");

	// ---- a ROLLBACK undoes the secrets made in its transaction; the next prelude makes them again
	auto d3 = Digest('3');
	Serve(d3, {b});
	Expect(Run(alice, MintFrom(url, "u-alice", d2, exp), "BEGIN TRANSACTION").empty(), "BEGIN");
	Expect(Run(alice, MintFrom(url, "u-alice", d3, exp)).empty() && UserSecrets(alice).size() == 1,
	       "inside the transaction, the d3 set");
	Expect(Run(alice, MintFrom(url, "u-alice", d3, exp), "ROLLBACK").empty(), "ROLLBACK");
	Expect(UserSecrets(alice).size() == 2, "the ROLLBACK restored the d2 secrets");
	auto requests = server.Requests();
	Expect(Run(alice, MintFrom(url, "u-alice", d3, exp)).empty(), "the next statement runs");
	secrets = UserSecrets(alice);
	Expect(secrets.size() == 1 && secrets.count("qod_uc_bbbbbbbb") == 1 && server.Requests() == requests,
	       "its prelude made the d3 set again, from the cache");

	// ---- a ROLLBACK that restores an earlier secret under a name the current set also uses
	auto b2 = b;
	b2.secret = "alice-secret-4";
	auto d5 = Digest('5');
	Serve(d5, {b2});
	Expect(Run(alice, MintFrom(url, "u-alice", d3, exp), "BEGIN TRANSACTION").empty(), "BEGIN again");
	Expect(Run(alice, MintFrom(url, "u-alice", d5, exp)).empty() &&
	           UserSecrets(alice)["qod_uc_bbbbbbbb"].find("secret=alice-secret-4;") != std::string::npos,
	       "inside the transaction, the d5 version of the same secret");
	Expect(Run(alice, MintFrom(url, "u-alice", d5, exp), "ROLLBACK").empty() &&
	           UserSecrets(alice)["qod_uc_bbbbbbbb"].find("secret=alice-secret-3;") != std::string::npos,
	       "the ROLLBACK restored the d3 version under the same name");
	Expect(Run(alice, MintFrom(url, "u-alice", d5, exp)).empty() &&
	           UserSecrets(alice)["qod_uc_bbbbbbbb"].find("secret=alice-secret-4;") != std::string::npos,
	       "the next prelude sees the restored version is not the d5 one and installs d5 again");

	// ---- a failed fetch on a connection that holds secrets: nothing runs under them until a prelude syncs
	Fail(503, "authorization context expired; retry");
	auto error = Run(alice, MintFrom(url, "u-alice", Digest('e'), exp));
	Expect(error.find("qod-node: S3 credentials unavailable, retry: authorization context expired; retry") !=
	           std::string::npos,
	       "a 503 for alice's new digest refuses the prelude");
	auto refused = alice.Query("SELECT 1");
	Expect(refused->HasError() && refused->GetError().find(qod::kNoPrincipal) != std::string::npos,
	       "no statement runs under the secrets alice held before the failed fetch");
	// The sentinel the manager sends while it cannot serve her set: always fetched, always refused.
	Fail(503, "credential set cannot be served now");
	error = Run(alice, MintFrom(url, "u-alice", qod::kUnavailableCreds, exp));
	Expect(error.find("qod-node: S3 credentials unavailable, retry: credential set cannot be served now") !=
	           std::string::npos,
	       "the unavailable digest is refused");
	refused = alice.Query("SELECT 1");
	Expect(refused->HasError() && refused->GetError().find(qod::kNoPrincipal) != std::string::npos,
	       "and no statement runs after it");
	Serve(qod::kUnavailableCreds, {b2});
	error = Run(alice, MintFrom(url, "u-alice", qod::kUnavailableCreds, exp));
	Expect(error.find("qod-node: S3 credentials refused: the answer does not verify: answer is for no credential "
	                  "set") != std::string::npos,
	       "even a signed answer for the unavailable digest is refused");
	refused = alice.Query("SELECT 1");
	Expect(refused->HasError() && refused->GetError().find(qod::kNoPrincipal) != std::string::npos,
	       "and still no statement runs");
	requests = server.Requests();
	Expect(Run(alice, MintFrom(url, "u-alice", d5, exp)).empty() && UserSecrets(alice).size() == 1 &&
	           UserSecrets(alice)["qod_uc_bbbbbbbb"].find("secret=alice-secret-4;") != std::string::npos &&
	           server.Requests() == requests,
	       "a prelude for a set the node holds recovers the connection");

	// ---- an empty digest drops them all, with no fetch
	Expect(Run(alice, MintFrom(url, "u-alice", "", exp)).empty() && UserSecrets(alice).empty() &&
	           server.Requests() == requests,
	       "an empty digest drops every user secret");
	Expect(Signer(alice, "s3://lake/shared/x.parquet") == "<none>", "and no secret signs her URLs any more");

	// ---- a failed fetch refuses the prelude, and every statement after it until a prelude succeeds
	Fail(503, "authorization context expired; retry");
	duckdb::Connection carol(db);
	TagAsQuackSession(carol);
	auto d4 = Digest('4');
	error = Run(carol, MintFrom(url, "u-carol", d4, exp));
	Expect(error.find("qod-node: S3 credentials unavailable, retry: authorization context expired; retry") !=
	           std::string::npos,
	       "a 503 refuses the prelude, retryable");
	auto after = carol.Query("SELECT 1");
	Expect(after->HasError() && after->GetError().find(qod::kNoPrincipal) != std::string::npos,
	       "no statement runs on a connection whose credentials failed");
	Serve(d4, {Entry('c', "s3://lake/carol/", "AKIACAROL", "carol-secret")});
	Expect(Run(carol, MintFrom(url, "u-carol", d4, exp)).empty() && UserSecrets(carol).size() == 1,
	       "the next prelude installs them and the statement runs");

	// ---- answers that do not verify are refused
	duckdb::Connection dave(db);
	TagAsQuackSession(dave);
	auto dave_entry = Entry('d', "s3://lake/dave/", "AKIADAVE", "dave-secret");
	Serve(Digest('6'), {dave_entry});
	error = Run(dave, MintFrom(url, "u-dave", Digest('5'), exp));
	Expect(error.find("qod-node: S3 credentials refused: the answer does not verify: answer is for another "
	                  "credential set") != std::string::npos,
	       "an answer for another digest");
	after = dave.Query("SELECT 1");
	Expect(after->HasError() && after->GetError().find(qod::kNoPrincipal) != std::string::npos,
	       "no statement runs on a connection whose answer was for another digest");
	Serve(Digest('5'), {dave_entry}, std::string(32, 'x'));
	error = Run(dave, MintFrom(url, "u-dave", Digest('5'), exp));
	Expect(error.find("qod-node: S3 credentials refused: the answer does not verify: answer MAC does not verify") !=
	           std::string::npos,
	       "an answer signed with another key");
	Expect(UserSecrets(dave).empty(), "and dave holds no secret");
	Serve(Digest('5'), {dave_entry});
	Expect(Run(dave, MintFrom(url, "u-dave", Digest('5'), exp)).empty() && UserSecrets(dave).size() == 1,
	       "the next verifying answer installs dave's secret and his statement runs");

	// ---- a prelude refused before its sync (the handle, or its install) also stops every statement
	// that follows, whatever an earlier prelude left installed
	error = Run(dave, MintFrom(url, "u-mallory", Digest('5'), exp));
	Expect(error.find("names another principal") != std::string::npos, "a handle for another principal is refused");
	after = dave.Query("SELECT 1");
	Expect(after->HasError() && after->GetError().find(qod::kNoPrincipal) != std::string::npos,
	       "no statement runs after a refused install, under dave's earlier principal and secrets");
	Expect(Run(dave, MintFrom(url, "u-dave", Digest('5'), exp)).empty(),
	       "a prelude that succeeds whole makes the connection ready again");
	// A handle another connection used. (The same connection binding its own last handle again is a
	// rebind, which Install admits; on a node the parse screen refuses a used nonce before any bind.)
	auto replayed = MintFrom(url, "u-dave", Digest('5'), exp);
	duckdb::Connection dave2(db);
	TagAsQuackSession(dave2);
	Expect(Run(dave2, replayed).empty(), "dave's handle installs on his other connection");
	error = Run(dave, replayed);
	Expect(error.find("it was already used") != std::string::npos, "a replayed handle is refused");
	after = dave.Query("SELECT 1");
	Expect(after->HasError() && after->GetError().find(qod::kNoPrincipal) != std::string::npos,
	       "no statement runs after a replayed handle");
	error = Run(dave, "AAAA.0000000000000000000000000000000000000000000000000000000000000000");
	Expect(error.find(qod::kHandleRefused) != std::string::npos, "a handle that does not verify is refused");
	after = dave.Query("SELECT 1");
	Expect(after->HasError() && after->GetError().find(qod::kNoPrincipal) != std::string::npos,
	       "no statement runs after a handle that does not verify");
	Expect(Run(dave, MintFrom(url, "u-dave", Digest('5'), exp)).empty() && UserSecrets(dave).size() == 1,
	       "and the next good prelude recovers it");

	// ---- the prelude prepared then executed, as quack sends it, autocommit and in a transaction
	duckdb::Connection frank(db);
	TagAsQuackSession(frank);
	auto frank_entry = Entry('f', "s3://lake/frank/", "AKIAFRANK", "frank-secret");
	Serve(Digest('a'), {frank_entry});
	requests = server.Requests();
	Expect(RunPrepared(frank, MintFrom(url, "u-frank", Digest('a'), exp)).empty() &&
	           UserSecrets(frank)["qod_uc_ffffffff"].find("secret=frank-secret;") != std::string::npos &&
	           server.Requests() == requests + 1,
	       "a prepared prelude installs frank's secret, and it stays after the PREPARE's transaction");
	Expect(Signer(frank, "s3://lake/frank/x.parquet") == "qod_uc_ffffffff", "frank's URLs are signed with it");
	Expect(RunPrepared(frank, MintFrom(url, "u-frank", Digest('a'), exp)).empty() && server.Requests() == requests + 1,
	       "the same digest prepared again installs nothing and fetches nothing");
	auto frank2 = frank_entry;
	frank2.secret = "frank-secret-2";
	Serve(Digest('b'), {frank2});
	Expect(RunPrepared(frank, MintFrom(url, "u-frank", Digest('a'), exp), "BEGIN TRANSACTION").empty(), "BEGIN");
	Expect(RunPrepared(frank, MintFrom(url, "u-frank", Digest('b'), exp)).empty() &&
	           UserSecrets(frank)["qod_uc_ffffffff"].find("secret=frank-secret-2;") != std::string::npos,
	       "a prepared prelude in a transaction installs the new set there");
	Expect(RunPrepared(frank, MintFrom(url, "u-frank", Digest('b'), exp), "COMMIT").empty() &&
	           UserSecrets(frank)["qod_uc_ffffffff"].find("secret=frank-secret-2;") != std::string::npos,
	       "COMMIT keeps it");
	auto frank3 = frank_entry;
	frank3.secret = "frank-secret-3";
	Serve(Digest('c'), {frank3});
	Expect(RunPrepared(frank, MintFrom(url, "u-frank", Digest('b'), exp), "BEGIN TRANSACTION").empty() &&
	           RunPrepared(frank, MintFrom(url, "u-frank", Digest('c'), exp)).empty() &&
	           RunPrepared(frank, MintFrom(url, "u-frank", Digest('c'), exp), "ROLLBACK").empty() &&
	           UserSecrets(frank)["qod_uc_ffffffff"].find("secret=frank-secret-2;") != std::string::npos,
	       "a ROLLBACK restores the committed version");
	Expect(RunPrepared(frank, MintFrom(url, "u-frank", Digest('c'), exp)).empty() &&
	           UserSecrets(frank)["qod_uc_ffffffff"].find("secret=frank-secret-3;") != std::string::npos,
	       "and the next prepared prelude installs the current set again");
	Fail(503, "authorization context expired; retry");
	error = RunPrepared(frank, MintFrom(url, "u-frank", Digest('d'), exp));
	Expect(error.find("qod-node: S3 credentials unavailable, retry") != std::string::npos,
	       "a prepared prelude whose fetch fails is refused");
	auto prepared_after = frank.Prepare("SELECT 1");
	Expect(prepared_after->HasError() && prepared_after->GetError().find(qod::kNoPrincipal) != std::string::npos,
	       "and nothing can be prepared after it");

	// ---- the manager itself never fetches nor holds any; a superuser's own are installed like anyone's
	requests = server.Requests();
	duckdb::Connection manager(db);
	TagAsQuackSession(manager);
	Expect(Run(manager, MintFrom(url, "system", Digest('7'), exp, false, true)).empty() &&
	           UserSecrets(manager).empty() && server.Requests() == requests,
	       "the system principal fetches nothing");
	Serve(Digest('8'), {Entry('e', "s3://lake/", "AKIAROOT", "root-secret")});
	duckdb::Connection root(db);
	TagAsQuackSession(root);
	Expect(Run(root, MintFrom(url, "u-root", Digest('8'), exp, true)).empty() && UserSecrets(root).size() == 1,
	       "a superuser's own credentials are installed");

	// ---- the scopes qod_session installs reach the parse screen, which refuses an s3:// location a
	// node secret would sign over the user's own (decision 23). The screen asks the replica only after:
	// this one answers with credentials, never a verdict, so a location that passes is refused there.
	duckdb::Connection internal(db);
	internal.context->RunFunctionInTransaction([&] {
		auto secret = duckdb::make_uniq<duckdb::KeyValueSecret>(duckdb::vector<std::string> {"s3://lake/ice/"},
		                                                        duckdb::Identifier("s3"), duckdb::Identifier("config"),
		                                                        duckdb::Identifier("qod_ice_e2e"));
		auto transaction = duckdb::CatalogTransaction::GetSystemCatalogTransaction(*internal.context);
		duckdb::SecretManager::Get(*internal.context)
		    .RegisterSecret(transaction, std::move(secret), duckdb::OnCreateConflict::ERROR_ON_CONFLICT,
		                    duckdb::SecretPersistType::TEMPORARY, "memory");
	});
	auto screen = [&](const std::string &connection_id, const std::string &sql) {
		auto result = internal.Query(std::string("SELECT ") + qod::kScreenFunction + "(" +
		                             qod::QuoteLiteral(connection_id) + ", " + qod::QuoteLiteral(sql) + ")");
		return result->HasError() ? result->GetError() : std::string();
	};
	auto read_ice = std::string("SELECT * FROM read_parquet('s3://lake/ice/t/x.parquet')");
	struct Case {
		const char *user;
		char digit;
		const char *scope;
		bool shadowed;
		const char *what;
	};
	for (auto &c : {Case {"u-gwen", '9', "s3://lake/ice/", false, "a credential scoped as closely as the node's"},
	                Case {"u-hugo", '6', "s3://lake/", true, "a credential scoped more widely than the node's"}}) {
		auto digest = Digest(c.digit);
		Serve(digest, {Entry(c.digit, c.scope, "AKIAUSER", "user-secret")});
		auto handle = MintFrom(url, c.user, digest, exp);
		duckdb::Connection user(db);
		TagAsQuackSession(user);
		auto connection_id = std::string("c-") + c.user;
		Expect(screen(connection_id, qod_test::Prelude(handle)).empty() && Run(user, handle).empty(),
		       std::string(c.what) + ": the prelude installs");
		auto refusal = screen(connection_id, read_ice);
		auto shadowed = refusal.find("would be signed with this node's own S3 keys") != std::string::npos;
		Expect(shadowed == c.shadowed && (c.shadowed || refusal.find("verdict does not verify") != std::string::npos),
		       std::string(c.what) + ": " + refusal);
	}

	if (failures == 0) {
		std::cout << "credentials_engine_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
