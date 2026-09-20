package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Branch REST surface (Epic 1), its own object for the `<clinit>` 64KB reason shared by every
  * endpoint module. POSTs carry tenant in the body like [[RestoreEndpoints]]; GETs carry it in the
  * path. Every endpoint takes the session input; the handlers enforce [[TenantScopeCheck]] per
  * request.
  */
object BranchEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  private val branchPath =
    "branch" / "tenant" / path[String]("tenant") /
      "database" / path[String]("tenantDb") / "branches"

  val createEndpoint: PublicEndpoint[
    (BranchCreateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    BranchEntry,
    Any
  ] =
    base.post
      .in("branch" / "create")
      .in(jsonBody[BranchCreateRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[BranchEntry])
      .description(
        "Create a writable branch of a DuckLake tenant-db: a zero-copy clone of the catalog at " +
          "the current head, served by its own pool. Target it with the FlightSQL `branch` " +
          "connection header or the MCP `branch` argument. 409 duplicate / branch_limit / " +
          "fork_snapshot_unsupported (v1 forks at head only); 400 invalid_name / " +
          "branch_of_branch_unsupported; 403 acl_denied when the caller cannot connect to the " +
          "parent."
      )

  val listEndpoint: PublicEndpoint[
    (String, String, Option[Boolean], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    BranchListResponse,
    Any
  ] =
    base.get
      .in(branchPath)
      .in(query[Option[Boolean]]("includeTerminal"))
      .in(Endpoints.authToken)
      .out(jsonBody[BranchListResponse])
      .description(
        "List the live branches of a tenant-db (open or proposed); includeTerminal=true also " +
          "returns merged, discarded and expired history rows."
      )

  val getEndpoint: PublicEndpoint[
    (String, String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    BranchDetailResponse,
    Any
  ] =
    base.get
      .in(branchPath / path[String]("branch"))
      .in(Endpoints.authToken)
      .out(jsonBody[BranchDetailResponse])
      .description("One branch (live or terminal) with its merge history.")

  val changesEndpoint: PublicEndpoint[
    (String, String, String, Option[Boolean], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    BranchChangesResponse,
    Any
  ] =
    base.get
      .in(branchPath / path[String]("branch") / "changes")
      .in(query[Option[Boolean]]("counts"))
      .in(Endpoints.authToken)
      .out(jsonBody[BranchChangesResponse])
      .description(
        "The branch's change set since its fork: touched tables classified as created, dropped, " +
          "recreated, modified or altered (with insert/delete/update counts unless counts=false), " +
          "the conflicts against main, and the changes v1 cannot merge. `mergeable` is the " +
          "fast-forward verdict."
      )

  val diffEndpoint: PublicEndpoint[
    (
        String,
        String,
        String,
        String,
        String,
        Option[Int],
        Option[String],
        Option[String],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    DataDiffResponse,
    Any
  ] =
    base.get
      .in(branchPath / path[String]("branch") / "diff")
      .in(query[String]("schema"))
      .in(query[String]("table"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[String]]("cursor"))
      .in(query[Option[String]]("changeType"))
      .in(Endpoints.authToken)
      .out(jsonBody[DataDiffResponse])
      .description(
        "Row-level diff of one table between the branch's fork snapshot and its head: the same " +
          "paginated change feed as the catalog data-diff (keyset cursor, changeType filter)."
      )

  val schemaDiffEndpoint: PublicEndpoint[
    (String, String, String, String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    SchemaDiffResponse,
    Any
  ] =
    base.get
      .in(branchPath / path[String]("branch") / "schema-diff")
      .in(query[String]("schema"))
      .in(query[String]("table"))
      .in(Endpoints.authToken)
      .out(jsonBody[SchemaDiffResponse])
      .description(
        "Column-level diff of one table between the branch's fork snapshot and its head."
      )

  val proposeEndpoint: PublicEndpoint[
    (BranchOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    BranchProposeResponse,
    Any
  ] =
    base.post
      .in("branch" / "propose")
      .in(jsonBody[BranchOpRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[BranchProposeResponse])
      .description(
        "Propose the branch for merge: records a merge request with the change set and the " +
          "conflicts computed now. A branch with conflicts can be proposed; merge refuses it. " +
          "409 already_proposed."
      )

  val mergeEndpoint: PublicEndpoint[
    (BranchMergeRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    BranchMergeResponse,
    Any
  ] =
    base.post
      .in("branch" / "merge")
      .in(jsonBody[BranchMergeRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[BranchMergeResponse])
      .description(
        "Fast-forward merge a proposed branch onto main in one stamped snapshot, tag it, and " +
          "tear the branch down. Human-gated: the approver must differ from the proposer (403 " +
          "self_merge_forbidden) and no MCP tool exposes it. 409 merge_conflict lists the tables " +
          "main changed since the fork; 409 concurrent_write on an expectedMainSnapshot mismatch " +
          "or a lost commit race (retry); 422 merge_unsupported for schema changes, views and " +
          "macros; 502 merge_failed."
      )

  val discardEndpoint: PublicEndpoint[
    (BranchOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    BranchEntry,
    Any
  ] =
    base.post
      .in("branch" / "discard")
      .in(jsonBody[BranchOpRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[BranchEntry])
      .description(
        "Discard a live branch (owner or tenant admin): its pool, catalog and branch-only files " +
          "are freed; the row stays as history."
      )
