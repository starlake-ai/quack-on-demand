package ai.starlake.quack.docs

import org.scalatest.funsuite.AnyFunSuite

class DocEndpointsSpec extends AnyFunSuite:

  test("collects a substantial, deduplicated set of endpoints from both objects") {
    val all = DocEndpoints.all
    // Endpoints has ~37 public endpoint vals; RbacEndpoints adds ~25. Floor guards
    // against silent reflection breakage (e.g. a rename that returns zero).
    assert(all.size >= 50, s"expected >= 50 endpoints, got ${all.size}")
  }

  test("every collected endpoint has a concrete method + path (no anonymous/base leakage)") {
    val keys = DocEndpoints.all.map(DocEndpoints.routeKey)
    assert(keys.forall(_.nonEmpty), "an endpoint produced an empty (method, path) key")
    assert(keys.distinct.size == keys.size, s"duplicate routes: ${keys.diff(keys.distinct).distinct}")
  }
