package ai.starlake.quack.edge.meta

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** qod-node admits a non-superuser's read of exactly the information_schema views the gateway's
  * metadata filter rewrites (`kFilteredListings` in describer.cpp, final re-review minor): a view
  * the node admits and the filter does not rewrite would be read unfiltered, and one the filter
  * rewrites and the node refuses would be refused to every tenant user.
  */
class FilteredListingsParitySpec extends AnyFlatSpec with Matchers:

  "qod-node's filtered listings" should "be the metadata filter's filterable tables" in {
    val source = Files.readString(Paths.get("native/qod-node/src/describer.cpp"))
    val set    = """kFilteredListings\s*=\s*\{([^}]*)\}""".r
      .findFirstMatchIn(source)
      .getOrElse(fail("describer.cpp declares no kFilteredListings"))
      .group(1)
    "\"([^\"]+)\"".r.findAllMatchIn(set).map(_.group(1)).toSet shouldBe
      MetadataFilterRewriter.FilterableTables
  }
