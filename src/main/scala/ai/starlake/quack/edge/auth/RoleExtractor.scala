package ai.starlake.quack.edge.auth

import com.nimbusds.jwt.JWTClaimsSet
import scala.jdk.CollectionConverters.*

/** Extracts role from JWT claims with a configurable claim name and fallback chain. */
object RoleExtractor:

  /** Extract role from a Nimbus JWTClaimsSet.
    *
    * Fallback chain:
    *   1. Configured roleClaim (e.g., "role")
    *   2. "roles" (as array, take first)
    *   3. "realm_access" -> "roles" (Keycloak nested)
    *   4. "cognito:groups" (AWS Cognito)
    *   5. Default: "user"
    */
  def extract(claims: JWTClaimsSet, roleClaim: String): String =
    // 1. Try configured claim as a string
    tryStringClaim(claims, roleClaim)
      // 2. Try configured claim as a list (take first)
      .orElse(tryListClaim(claims, roleClaim))
      // 3. Try "roles" as a list
      .orElse(tryListClaim(claims, "roles"))
      // 4. Try Keycloak nested "realm_access.roles"
      .orElse(tryKeycloakRealmAccess(claims))
      // 5. Try AWS Cognito "cognito:groups"
      .orElse(tryListClaim(claims, "cognito:groups"))
      .getOrElse("user")

  /** Extract role from a flat string claims map. */
  def extractFromMap(claims: Map[String, String], roleClaim: String): String =
    claims
      .get(roleClaim)
      .orElse(claims.get("roles"))
      .orElse(claims.get("cognito:groups"))
      .getOrElse("user")

  /** Extract groups from a Nimbus JWTClaimsSet. */
  def extractGroups(claims: JWTClaimsSet, groupsClaim: String): Set[String] =
    tryListAllClaim(claims, groupsClaim)
      .orElse(tryListAllClaim(claims, "groups"))
      .orElse(tryListAllClaim(claims, "cognito:groups"))
      .orElse(tryKeycloakRealmAccessAll(claims))
      .getOrElse(Set.empty)

  private def tryStringClaim(claims: JWTClaimsSet, name: String): Option[String] =
    try Option(claims.getStringClaim(name)).filter(_.nonEmpty)
    catch case _: Exception => None

  private def tryListClaim(claims: JWTClaimsSet, name: String): Option[String] =
    try
      Option(claims.getStringListClaim(name))
        .flatMap(_.asScala.headOption)
    catch case _: Exception => None

  private def tryListAllClaim(claims: JWTClaimsSet, name: String): Option[Set[String]] =
    try
      Option(claims.getStringListClaim(name))
        .map(_.asScala.toSet)
        .filter(_.nonEmpty)
    catch case _: Exception => None

  private def tryKeycloakRealmAccess(claims: JWTClaimsSet): Option[String] =
    try
      Option(claims.getJSONObjectClaim("realm_access"))
        .flatMap { ra =>
          Option(ra.get("roles")).collect { case list: java.util.List[?] =>
            list.asScala.collect { case s: String => s }.headOption
          }.flatten
        }
    catch case _: Exception => None

  private def tryKeycloakRealmAccessAll(claims: JWTClaimsSet): Option[Set[String]] =
    try
      Option(claims.getJSONObjectClaim("realm_access"))
        .flatMap { ra =>
          Option(ra.get("roles"))
            .collect { case list: java.util.List[?] =>
              list.asScala.collect { case s: String => s: String }.toSet[String]
            }
            .filter(_.nonEmpty)
        }
    catch case _: Exception => None
