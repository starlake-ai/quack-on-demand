package ai.starlake.quack.edge.meta

import ai.starlake.acl.parser.TableExtractor
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Pins the DuckDB quack client's catalog-sync queries to what the metadata filter can rewrite
  * (issue #114). On `ATTACH ... (TYPE quack)` the client loads the remote catalog through the
  * `GetLoadQuery` statements of the vendored extension (`native/quackwire/thirdparty/duckdb-quack`,
  * a git submodule); every `duckdb_%` table function and every `information_schema` table those
  * statements name must be in [[TableExtractor.DuckDbCatalogFunctions]] /
  * [[MetadataFilterRewriter.FilterableTables]], or the attach is denied again for every principal
  * without a wildcard ALL grant, with the exact error the issue reported.
  *
  * The scan is textual over the client's storage sources: coarser than parsing each query, but it
  * cannot under-count, and a name it catches outside a sync query only ever widens what must be
  * filterable. Cancelled when the submodule is not checked out (a plain clone without
  * `--recursive`, or a CI job that does not fetch it), never passed vacuously: the scan must find
  * the two functions the sync is known to use.
  */
class QuackClientSyncFunctionsSpec extends AnyFlatSpec with Matchers:

  private val storageDir: Path =
    Paths.get("native", "quackwire", "thirdparty", "duckdb-quack", "src", "storage")

  private val FunctionRe   = """\bduckdb_[a-z_]+(?=\s*\()""".r
  private val InfoSchemaRe = """\binformation_schema\.([a-z_]+)""".r

  private def clientSources: List[Path] =
    Files
      .walk(storageDir)
      .iterator()
      .asScala
      .filter(p => p.toString.endsWith(".cpp") || p.toString.endsWith(".hpp"))
      .toList

  "the quack client's catalog sync" should "use only catalog functions the metadata filter rewrites" in {
    assume(Files.isDirectory(storageDir), s"duckdb-quack submodule not checked out at $storageDir")
    val text      = clientSources.map(Files.readString).mkString("\n")
    val functions = FunctionRe.findAllIn(text).toSet
    withClue("scan found nothing: the client's load queries moved, update storageDir") {
      functions should contain allOf ("duckdb_tables", "duckdb_views")
    }
    withClue(
      "the client syncs through a catalog function the filter does not rewrite; add it to " +
        "TableExtractor.DuckDbCatalogFunctions AND give functionPredicateFor its column names"
    ) {
      functions diff TableExtractor.DuckDbCatalogFunctions shouldBe empty
    }
  }

  it should "use only information_schema tables the metadata filter rewrites" in {
    assume(Files.isDirectory(storageDir), s"duckdb-quack submodule not checked out at $storageDir")
    val text   = clientSources.map(Files.readString).mkString("\n")
    val tables = InfoSchemaRe.findAllMatchIn(text).map(_.group(1)).toSet
    tables should contain("schemata")
    tables diff MetadataFilterRewriter.FilterableTables shouldBe empty
  }
