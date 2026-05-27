package ai.starlake.acl.policy

import ai.starlake.acl.model.{Config, TableRef}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ViewResolverTest extends AnyFlatSpec with Matchers {

  private val testConfig = Config.forGeneric("testdb", "testsch")

  private def ref(db: String, schema: String, table: String): TableRef =
    TableRef(db, schema, table)

  // ---------------------------------------------------------------------------
  // Base table resolution
  // ---------------------------------------------------------------------------

  "ViewResolver" should "resolve base tables with no view expansion" in {
    val t1 = ref("db", "sch", "t1")
    val t2 = ref("db", "sch", "t2")

    val resolver = ViewResolver(
      _ => ResourceLookupResult.BaseTable,
      testConfig
    )

    val result = resolver.resolve(Set(t1, t2))
    result.resolutions(t1) shouldBe SingleTableResolution.Base
    result.resolutions(t2) shouldBe SingleTableResolution.Base
    result.resolutionMap shouldBe empty
  }

  // ---------------------------------------------------------------------------
  // Simple view resolution
  // ---------------------------------------------------------------------------

  it should "resolve a simple view to its underlying tables" in {
    val v1 = ref("testdb", "testsch", "v1")
    val t1 = ref("testdb", "testsch", "t1")
    val t2 = ref("testdb", "testsch", "t2")

    val resolver = ViewResolver(
      {
        case r if r == v1 => ResourceLookupResult.View("SELECT * FROM t1 JOIN t2 ON t1.id = t2.id")
        case _            => ResourceLookupResult.BaseTable
      },
      testConfig
    )

    val result = resolver.resolve(Set(v1))
    result.resolutions(v1) match {
      case SingleTableResolution.ResolvedView(deps, resolved) =>
        deps should contain(t1)
        deps should contain(t2)
        resolved(t1) shouldBe SingleTableResolution.Base
        resolved(t2) shouldBe SingleTableResolution.Base
      case other =>
        fail(s"Expected ResolvedView but got $other")
    }
    result.resolutionMap(v1) should contain allOf (t1, t2)
  }

  // ---------------------------------------------------------------------------
  // Nested view resolution
  // ---------------------------------------------------------------------------

  it should "resolve nested views (v1 -> v2 -> t1)" in {
    val v1 = ref("testdb", "testsch", "v1")
    val v2 = ref("testdb", "testsch", "v2")
    val t1 = ref("testdb", "testsch", "t1")

    val resolver = ViewResolver(
      {
        case r if r == v1 => ResourceLookupResult.View("SELECT * FROM v2")
        case r if r == v2 => ResourceLookupResult.View("SELECT * FROM t1")
        case _            => ResourceLookupResult.BaseTable
      },
      testConfig
    )

    val result = resolver.resolve(Set(v1))
    result.resolutions(v1) match {
      case SingleTableResolution.ResolvedView(deps, resolved) =>
        deps should contain(v2)
        resolved(v2) match {
          case SingleTableResolution.ResolvedView(innerDeps, innerResolved) =>
            innerDeps should contain(t1)
            innerResolved(t1) shouldBe SingleTableResolution.Base
          case other =>
            fail(s"Expected inner ResolvedView but got $other")
        }
      case other =>
        fail(s"Expected ResolvedView but got $other")
    }
    result.resolutionMap(v1) should contain(t1)
  }

  // ---------------------------------------------------------------------------
  // Cycle detection (two-node)
  // ---------------------------------------------------------------------------

  it should "detect a two-node cycle (v1 -> v2 -> v1)" in {
    val v1 = ref("testdb", "testsch", "v1")
    val v2 = ref("testdb", "testsch", "v2")

    val resolver = ViewResolver(
      {
        case r if r == v1 => ResourceLookupResult.View("SELECT * FROM v2")
        case r if r == v2 => ResourceLookupResult.View("SELECT * FROM v1")
        case _            => ResourceLookupResult.BaseTable
      },
      testConfig
    )

    val result = resolver.resolve(Set(v1))
    result.resolutions(v1) match {
      case SingleTableResolution.Cycle(path) =>
        path should contain(v1)
        path should contain(v2)
        // The cycle path should show the full chain ending with the repeated node
        path.last shouldBe v1
      case other =>
        fail(s"Expected Cycle but got $other")
    }
  }

  // ---------------------------------------------------------------------------
  // Cycle detection (three-node)
  // ---------------------------------------------------------------------------

  it should "detect a three-node cycle (v1 -> v2 -> v3 -> v1)" in {
    val v1 = ref("testdb", "testsch", "v1")
    val v2 = ref("testdb", "testsch", "v2")
    val v3 = ref("testdb", "testsch", "v3")

    val resolver = ViewResolver(
      {
        case r if r == v1 => ResourceLookupResult.View("SELECT * FROM v2")
        case r if r == v2 => ResourceLookupResult.View("SELECT * FROM v3")
        case r if r == v3 => ResourceLookupResult.View("SELECT * FROM v1")
        case _            => ResourceLookupResult.BaseTable
      },
      testConfig
    )

    val result = resolver.resolve(Set(v1))
    result.resolutions(v1) match {
      case SingleTableResolution.Cycle(path) =>
        path should have size 4 // v1 -> v2 -> v3 -> v1
        path.head shouldBe v1
        path.last shouldBe v1
      case other =>
        fail(s"Expected Cycle but got $other")
    }
  }

  // ---------------------------------------------------------------------------
  // Diamond dependency (NOT a cycle)
  // ---------------------------------------------------------------------------

  it should "handle diamond dependencies without flagging a cycle" in {
    val v1 = ref("testdb", "testsch", "v1")
    val v2 = ref("testdb", "testsch", "v2")
    val t1 = ref("testdb", "testsch", "t1")
    val t2 = ref("testdb", "testsch", "t2")

    // v1 references t1 and v2. v2 references t1 and t2.
    // t1 appears in both branches -- this is a diamond, NOT a cycle.
    val resolver = ViewResolver(
      {
        case r if r == v1 => ResourceLookupResult.View("SELECT * FROM t1 JOIN v2 ON t1.id = v2.id")
        case r if r == v2 => ResourceLookupResult.View("SELECT * FROM t1 JOIN t2 ON t1.id = t2.id")
        case _            => ResourceLookupResult.BaseTable
      },
      testConfig
    )

    val result = resolver.resolve(Set(v1))
    result.resolutions(v1) match {
      case SingleTableResolution.ResolvedView(deps, resolved) =>
        deps should contain(t1)
        deps should contain(v2)
        resolved(t1) shouldBe SingleTableResolution.Base
        resolved(v2) match {
          case SingleTableResolution.ResolvedView(innerDeps, innerResolved) =>
            innerDeps should contain(t1)
            innerDeps should contain(t2)
            innerResolved(t1) shouldBe SingleTableResolution.Base
            innerResolved(t2) shouldBe SingleTableResolution.Base
          case other =>
            fail(s"Expected inner ResolvedView but got $other")
        }
      case other =>
        fail(s"Expected ResolvedView but got $other")
    }
    // Transitive base tables for v1 should include t1 and t2
    result.resolutionMap(v1) should contain allOf (t1, t2)
  }

  // ---------------------------------------------------------------------------
  // Unknown table
  // ---------------------------------------------------------------------------

  it should "return UnknownRef for tables the callback reports as Unknown" in {
    val t1 = ref("db", "sch", "t1")

    val resolver = ViewResolver(
      _ => ResourceLookupResult.Unknown,
      testConfig
    )

    val result = resolver.resolve(Set(t1))
    result.resolutions(t1) shouldBe SingleTableResolution.UnknownRef
  }

  // ---------------------------------------------------------------------------
  // Callback exception
  // ---------------------------------------------------------------------------

  it should "return Error when the callback throws an exception" in {
    val t1 = ref("db", "sch", "t1")

    val resolver = ViewResolver(
      _ => throw new RuntimeException("Connection refused"),
      testConfig
    )

    val result = resolver.resolve(Set(t1))
    result.resolutions(t1) match {
      case SingleTableResolution.Error(msg) =>
        msg should include("Connection refused")
      case other =>
        fail(s"Expected Error but got $other")
    }
  }

  // ---------------------------------------------------------------------------
  // View SQL parse error
  // ---------------------------------------------------------------------------

  it should "return ParseError when view SQL cannot be parsed" in {
    val v1 = ref("testdb", "testsch", "v1")

    val resolver = ViewResolver(
      _ => ResourceLookupResult.View("INVALID SQL !!!"),
      testConfig
    )

    val result = resolver.resolve(Set(v1))
    result.resolutions(v1) match {
      case SingleTableResolution.ParseError(sql, msg) =>
        sql shouldBe "INVALID SQL !!!"
        msg should not be empty
      case other =>
        fail(s"Expected ParseError but got $other")
    }
  }

  // ---------------------------------------------------------------------------
  // View with no tables (SELECT 1)
  // ---------------------------------------------------------------------------

  it should "resolve a view with no tables (SELECT 1) as an empty ResolvedView" in {
    val v1 = ref("testdb", "testsch", "v1")

    val resolver = ViewResolver(
      _ => ResourceLookupResult.View("SELECT 1"),
      testConfig
    )

    val result = resolver.resolve(Set(v1))
    result.resolutions(v1) match {
      case SingleTableResolution.ResolvedView(deps, resolved) =>
        deps shouldBe empty
        resolved shouldBe empty
      case other =>
        fail(s"Expected ResolvedView but got $other")
    }
  }

  // ---------------------------------------------------------------------------
  // Mixed results
  // ---------------------------------------------------------------------------

  it should "handle mixed base tables, views, and unknowns together" in {
    val t1      = ref("testdb", "testsch", "t1")
    val v1      = ref("testdb", "testsch", "v1")
    val unknown = ref("testdb", "testsch", "mystery")
    val t2      = ref("testdb", "testsch", "t2")

    val resolver = ViewResolver(
      {
        case r if r == t1      => ResourceLookupResult.BaseTable
        case r if r == v1      => ResourceLookupResult.View("SELECT * FROM t2")
        case r if r == unknown => ResourceLookupResult.Unknown
        case _                 => ResourceLookupResult.BaseTable
      },
      testConfig
    )

    val result = resolver.resolve(Set(t1, v1, unknown))
    result.resolutions(t1) shouldBe SingleTableResolution.Base
    result.resolutions(v1) match {
      case SingleTableResolution.ResolvedView(deps, _) =>
        deps should contain(t2)
      case other =>
        fail(s"Expected ResolvedView but got $other")
    }
    result.resolutions(unknown) shouldBe SingleTableResolution.UnknownRef
  }

  // ---------------------------------------------------------------------------
  // Caching verification
  // ---------------------------------------------------------------------------

  it should "call the callback at most once per TableRef per resolve() invocation" in {
    val v1 = ref("testdb", "testsch", "v1")
    val v2 = ref("testdb", "testsch", "v2")
    val expectedT1 = ref("testdb", "testsch", "t1")

    // v1 and v2 both reference t1 (diamond)
    var callCount = 0
    val resolver = ViewResolver(
      { ref =>
        callCount += 1
        ref match {
          case r if r == v1 => ResourceLookupResult.View("SELECT * FROM t1")
          case r if r == v2 => ResourceLookupResult.View("SELECT * FROM t1")
          case _            => ResourceLookupResult.BaseTable
        }
      },
      testConfig
    )

    val result = resolver.resolve(Set(v1, v2))
    // t1 appears in both v1 and v2, but callback should be called once for t1
    // Total: v1 (1) + v2 (1) + t1 (1) = 3
    callCount shouldBe 3
    result.resolutions(v1) shouldBe a[SingleTableResolution.ResolvedView]
    result.resolutions(v2) shouldBe a[SingleTableResolution.ResolvedView]
    result.resolutionMap(v1) should contain(expectedT1)
  }

  it should "reset cache between resolve() invocations" in {
    val t1 = ref("testdb", "testsch", "t1")

    var callCount = 0
    val resolver = ViewResolver(
      { _ =>
        callCount += 1
        ResourceLookupResult.BaseTable
      },
      testConfig
    )

    val result1 = resolver.resolve(Set(t1))
    callCount shouldBe 1
    result1.resolutions(t1) shouldBe SingleTableResolution.Base

    val result2 = resolver.resolve(Set(t1))
    callCount shouldBe 2 // Called again because cache was cleared
    result2.resolutions(t1) shouldBe SingleTableResolution.Base
  }

  // ---------------------------------------------------------------------------
  // Max depth exceeded
  // ---------------------------------------------------------------------------

  it should "return MaxDepthExceeded when view chain exceeds maxViewDepth" in {
    val v1 = ref("testdb", "testsch", "v1")
    val v2 = ref("testdb", "testsch", "v2")
    val v3 = ref("testdb", "testsch", "v3")

    // v1 -> v2 -> v3 -> t1, but maxViewDepth=2 so v3 resolution exceeds depth
    val resolver = ViewResolver(
      {
        case r if r == v1 => ResourceLookupResult.View("SELECT * FROM v2")
        case r if r == v2 => ResourceLookupResult.View("SELECT * FROM v3")
        case r if r == v3 => ResourceLookupResult.View("SELECT * FROM t1")
        case _            => ResourceLookupResult.BaseTable
      },
      testConfig,
      maxViewDepth = 2
    )

    val result = resolver.resolve(Set(v1))
    result.resolutions(v1) match {
      case SingleTableResolution.MaxDepthExceeded(path) =>
        path should have size 3 // v1, v2, v3
      case other =>
        fail(s"Expected MaxDepthExceeded but got $other")
    }
  }

  it should "resolve successfully when view chain is within maxViewDepth" in {
    val v1 = ref("testdb", "testsch", "v1")
    val v2 = ref("testdb", "testsch", "v2")
    val t1 = ref("testdb", "testsch", "t1")

    // v1 -> v2 -> t1, maxViewDepth=3 is enough
    val resolver = ViewResolver(
      {
        case r if r == v1 => ResourceLookupResult.View("SELECT * FROM v2")
        case r if r == v2 => ResourceLookupResult.View("SELECT * FROM t1")
        case _            => ResourceLookupResult.BaseTable
      },
      testConfig,
      maxViewDepth = 3
    )

    val result = resolver.resolve(Set(v1))
    result.resolutions(v1) shouldBe a[SingleTableResolution.ResolvedView]
    result.resolutionMap(v1) should contain(t1)
  }

  it should "use default maxViewDepth of 50 when not specified" in {
    val t1 = ref("testdb", "testsch", "t1")

    val resolver = ViewResolver(
      _ => ResourceLookupResult.BaseTable,
      testConfig
    )

    // Default depth of 50 should easily handle base tables
    val result = resolver.resolve(Set(t1))
    result.resolutions(t1) shouldBe SingleTableResolution.Base
  }
}
