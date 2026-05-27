package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile

import java.time.Instant
import java.util.UUID
import scala.collection.concurrent.TrieMap

/** In-memory session token registry for UI logins. Keys are opaque UUIDs
  * returned to the browser; values carry the authenticated profile so the
  * admin-only middleware can decide whether to admit a request without
  * re-validating credentials on every call.
  *
  * Tokens live in memory — manager restart invalidates all sessions. v1
  * doesn't expire tokens; the UI logout drops the row and the user is
  * expected to log in again after a restart. */
final class SessionTokenStore:

  final case class Session(profile: AuthenticatedProfile, createdAt: Instant)

  private val sessions = TrieMap.empty[String, Session]

  /** Mint a new opaque token for the given profile. Returns the token. */
  def mint(profile: AuthenticatedProfile): String =
    val token = UUID.randomUUID().toString
    sessions.put(token, Session(profile, Instant.now()))
    token

  def get(token: String): Option[Session] = sessions.get(token)

  def revoke(token: String): Unit = { sessions.remove(token); () }

  def isAdmin(token: String): Boolean =
    sessions.get(token).exists(_.profile.role.equalsIgnoreCase("admin"))