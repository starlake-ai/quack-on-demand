package ai.starlake.quack.mcp

import ai.starlake.quack.McpConfig
import ai.starlake.quack.ondemand.auth.{SessionScope, TokenRestriction}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Drift guard for [[McpToolCatalog]]: its static list must name exactly the tools the real
  * `Mcp*Tools` classes register, with the same `adminOnly`, and exactly the reserved names of the
  * other surfaces. A tool added to a class without a catalog entry would be refused at mint
  * (`unknown_tool`) although MCP offers it, so this fails first.
  *
  * The classes are built over null handlers: each one only reads its handlers inside a tool's
  * `run`, never at construction, and `tools` is all this spec reads.
  */
class McpToolCatalogSpec extends AnyFlatSpec with Matchers:

  private val scope: SessionScope.Resolver = _ => SessionScope.Superuser

  /** Every tool `Main` hands `McpRoutes`, tier by tier, over null handlers. */
  private def registered: List[McpToolDef] =
    new McpDataTools(McpConfig(), null, null, null, null, null, null, null, scope).tools ++
      new McpBranchTools(null, scope).tools ++
      new McpAdminTools(null, null, null, null, null, null, null, scope).tools ++
      new McpIdentityTools(null, null, null, null, null, scope, null).tools ++
      new McpAccessTools(null, null, null, null, scope).tools ++
      new McpPlatformTools(null, null, None, null, null, null, null, null, scope).tools ++
      new McpProfileTools(null).tools

  "McpToolCatalog" should "name exactly the MCP tools the tool classes register" in {
    val registeredNames = registered.map(_.name).toSet
    val catalogNames    = McpToolCatalog.mcpTools.map(_.name).toSet
    withClue(
      s"registered, not in the catalog: ${registeredNames -- catalogNames}; " +
        s"in the catalog, not registered: ${catalogNames -- registeredNames}"
    ) {
      catalogNames shouldBe registeredNames
    }
  }

  it should "carry each tool's registered adminOnly flag" in {
    val registeredFlags = registered.map(t => t.name -> t.adminOnly).toMap
    McpToolCatalog.mcpTools.foreach { t =>
      withClue(t.name)(registeredFlags.get(t.name) shouldBe Some(t.adminOnly))
    }
  }

  it should "list each tier exactly as its class does" in {
    new McpDataTools(McpConfig(), null, null, null, null, null, null, null, scope).tools
      .map(_.name) shouldBe McpToolCatalog.dataTier.map(_.name)
    new McpBranchTools(null, scope).tools.map(_.name) shouldBe
      McpToolCatalog.branchTier.map(_.name)
    new McpProfileTools(null).tools.map(_.name) shouldBe McpToolCatalog.profileTier.map(_.name)
  }

  it should "name each tool once" in {
    val names = McpToolCatalog.entries.map(_.name)
    names.distinct should have size names.size.toLong
  }

  it should "name exactly the reserved names of the other surfaces" in {
    McpToolCatalog.surfaces.map(_.name).toSet shouldBe TokenRestriction.ReservedToolNames
    McpToolCatalog.mcpTools.map(_.name).toSet intersect TokenRestriction.ReservedToolNames shouldBe
      empty
  }

  it should "list rest as the REST data edge, a read-only surface" in {
    val rest = McpToolCatalog.entries.find(_.name == TokenRestriction.RestTool)
    rest.map(_.label) shouldBe Some("REST data edge")
    rest.map(_.group) shouldBe Some(McpToolCatalog.Group.Surface)
    rest.map(_.readOnly) shouldBe Some(true)
  }

  it should "group a tool as admin exactly when it is adminOnly" in {
    val byName = McpToolCatalog.entries.map(e => e.name -> e.group).toMap
    McpToolCatalog.mcpTools.foreach { t =>
      withClue(t.name) {
        byName(t.name) shouldBe
          (if t.adminOnly then McpToolCatalog.Group.Admin else McpToolCatalog.Group.Data)
      }
    }
  }

  it should "mark read-only exactly the tools an RO token keeps" in {
    McpToolCatalog.entries
      .filter(_.group != McpToolCatalog.Group.Surface)
      .filter(_.readOnly)
      .map(_.name)
      .toSet shouldBe McpRoutes.ReadOnlyTools
  }

  it should "sort data tools first, then admin tools, then surfaces, each by name" in {
    val entries = McpToolCatalog.entries
    entries shouldBe entries.sortBy(e => (e.group.ordinal, e.name))
    entries.map(_.group).distinct shouldBe List(
      McpToolCatalog.Group.Data,
      McpToolCatalog.Group.Admin,
      McpToolCatalog.Group.Surface
    )
  }

  it should "describe every name" in {
    McpToolCatalog.entries.filter(_.description.trim.isEmpty) shouldBe empty
  }

  "McpToolCatalog.unknown" should "return the names off the axis, sorted, once each" in {
    McpToolCatalog.unknown(List("run_sql", "zzz", "rest", "aaa", "zzz")) shouldBe List("aaa", "zzz")
    McpToolCatalog.unknown(Nil) shouldBe empty
  }
