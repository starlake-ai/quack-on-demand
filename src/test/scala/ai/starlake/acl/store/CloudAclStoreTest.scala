package ai.starlake.acl.store

import ai.starlake.acl.AclError
import ai.starlake.acl.model.TenantId
import blobstore.Store
import blobstore.s3.S3Blob
import blobstore.url.{FsObject, Path as BlobPath, Url}
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterEach, EitherValues}

import java.time.Instant

/** Unit tests for CloudAclStore with a mocked Store[IO, S3Blob].
  *
  * Uses real S3Blob (which extends FsObject) to avoid package-private
  * visibility issues with FsObject.generalStorageClass.
  */
class CloudAclStoreTest extends AnyFunSuite with Matchers with BeforeAndAfterEach with EitherValues:

  /** Simple metadata holder for test objects. */
  case class TestObj(
      name: String,
      content: Option[String] = None,
      isDir: Boolean = false,
      lastModified: Option[Instant] = None
  )

  /** Create an S3Blob from test metadata. */
  private def toS3Blob(obj: TestObj): S3Blob =
    import blobstore.s3.S3MetaInfo
    val meta = Some(new S3MetaInfo {
      override def size: Option[Long] = obj.content.map(_.length.toLong)
      override def lastModified: Option[Instant] = obj.lastModified
    })
    // S3Blob with isDir=false because files aren't dirs; dirs handled by path convention
    S3Blob(bucket = "test-bucket", key = obj.name, meta = if obj.isDir then None else meta)

  /** In-memory Store[IO, S3Blob] that simulates a cloud object store. */
  class MockStore(objects: Map[String, TestObj] = Map.empty) extends Store[IO, S3Blob]:

    private def normalizePrefix[A](url: Url[A]): String =
      val key = url.show
      if key.endsWith("/") then key else key + "/"

    override def list[A](url: Url[A], recursive: Boolean): Stream[IO, Url[S3Blob]] =
      val prefix = normalizePrefix(url)
      val entries = objects.toList.flatMap { case (key, obj) =>
        if !key.startsWith(prefix) || key == prefix then None
        else
          val relativePath = key.stripPrefix(prefix)
          if !recursive then
            val firstSlash = relativePath.indexOf('/')
            if firstSlash < 0 then
              // File at this level
              val blob = toS3Blob(obj)
              val path = BlobPath(relativePath).as(blob)
              Some(Url(url.scheme, url.authority, path))
            else if firstSlash == relativePath.length - 1 then
              // Directory marker at this level
              val dirObj = obj.copy(isDir = true)
              val blob = toS3Blob(dirObj)
              val path = BlobPath(relativePath).as(blob)
              Some(Url(url.scheme, url.authority, path))
            else
              // Deeper nested → synthetic directory prefix
              val dirName = relativePath.substring(0, firstSlash + 1)
              val blob = toS3Blob(TestObj(dirName, isDir = true))
              val path = BlobPath(dirName).as(blob)
              Some(Url(url.scheme, url.authority, path))
          else if !obj.isDir then
            val blob = toS3Blob(obj)
            val path = BlobPath(relativePath).as(blob)
            Some(Url(url.scheme, url.authority, path))
          else None
      }.distinctBy(_.path.show)
      Stream.emits(entries)

    override def get[A](url: Url[A], chunkSize: Int): Stream[IO, Byte] =
      val key = url.show
      objects.get(key) match
        case Some(TestObj(_, Some(content), _, _)) =>
          Stream.emits(content.getBytes("UTF-8"))
        case _ =>
          Stream.raiseError[IO](new java.io.FileNotFoundException(s"Not found: $key"))

    override def put[A](url: Url[A], overwrite: Boolean, size: Option[Long]): Pipe[IO, Byte, Unit] =
      _ => Stream.empty
    override def move[A, B](src: Url[A], dst: Url[B]): IO[Unit] = IO.unit
    override def copy[A, B](src: Url[A], dst: Url[B]): IO[Unit] = IO.unit
    override def remove[A](url: Url[A], recursive: Boolean): IO[Unit] = IO.unit
    override def putRotate[A](computeUrl: IO[Url[A]], limit: Long): Pipe[IO, Byte, Unit] =
      _ => Stream.empty
    override def stat[A](url: Url[A]): Stream[IO, Url[S3Blob]] = Stream.empty

  private var runtime: IORuntime = scala.compiletime.uninitialized
  private var closeCalled: Boolean = false

  override def beforeEach(): Unit =
    closeCalled = false
    val exec = java.util.concurrent.Executors.newCachedThreadPool { r =>
      val t = new Thread(r, "CloudAclStoreTest-worker")
      t.setDaemon(true)
      t
    }
    val ec = scala.concurrent.ExecutionContext.fromExecutorService(exec)
    val schedExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "CloudAclStoreTest-scheduler")
      t.setDaemon(true)
      t
    }
    val scheduler = cats.effect.unsafe.Scheduler.fromScheduledExecutor(schedExec)
    runtime = cats.effect.unsafe.IORuntime(
      ec, ec, scheduler,
      () => { exec.shutdown(); schedExec.shutdown() },
      cats.effect.unsafe.IORuntimeConfig()
    )

  override def afterEach(): Unit =
    runtime.shutdown()

  private def tenant(name: String): TenantId =
    TenantId.parse(name).toOption.get

  private def createStore(mockStore: MockStore): CloudAclStore[S3Blob] =
    val baseUrl = Url.unsafe("s3://test-bucket/acl")
    new CloudAclStore[S3Blob](mockStore, baseUrl, runtime, () => { closeCalled = true })

  // --- tenantExists ---

  test("tenantExists returns true when tenant has objects") {
    val mock = new MockStore(Map(
      "s3://test-bucket/acl/tenant-a/grants.yaml" ->
        TestObj("grants.yaml", Some("content"))
    ))
    val store = createStore(mock)
    store.tenantExists(tenant("tenant-a")) shouldBe true
  }

  test("tenantExists returns false when tenant has no objects") {
    val mock = new MockStore(Map.empty)
    val store = createStore(mock)
    store.tenantExists(tenant("nonexistent")) shouldBe false
  }

  // --- listYamlFiles ---

  test("listYamlFiles returns sorted YAML files, ignoring non-YAML and directories") {
    val mock = new MockStore(Map(
      "s3://test-bucket/acl/tenant-b/z.yaml" -> TestObj("z.yaml", Some("z")),
      "s3://test-bucket/acl/tenant-b/a.yml" -> TestObj("a.yml", Some("a")),
      "s3://test-bucket/acl/tenant-b/m.yaml" -> TestObj("m.yaml", Some("m")),
      "s3://test-bucket/acl/tenant-b/readme.md" -> TestObj("readme.md", Some("md")),
      "s3://test-bucket/acl/tenant-b/subfolder/" -> TestObj("subfolder/", isDir = true)
    ))
    val store = createStore(mock)
    store.listYamlFiles(tenant("tenant-b")).value shouldBe
      List("a.yml", "m.yaml", "z.yaml")
  }

  test("listYamlFiles returns Left(TenantNotFound) for nonexistent tenant") {
    val mock = new MockStore(Map.empty)
    val store = createStore(mock)
    val result = store.listYamlFiles(tenant("missing"))
    result.isLeft shouldBe true
    result.left.value shouldBe a[AclError.TenantNotFound]
  }

  test("listYamlFiles returns empty list for tenant with no YAML files") {
    val mock = new MockStore(Map(
      "s3://test-bucket/acl/empty/readme.md" -> TestObj("readme.md", Some("md"))
    ))
    val store = createStore(mock)
    store.listYamlFiles(tenant("empty")).value shouldBe List.empty
  }

  // --- readFile ---

  test("readFile returns file content as string") {
    val content = "grants:\n  - target: db.schema.table"
    val mock = new MockStore(Map(
      "s3://test-bucket/acl/tenant-d/acl.yaml" -> TestObj("acl.yaml", Some(content))
    ))
    val store = createStore(mock)
    store.readFile(tenant("tenant-d"), "acl.yaml").value shouldBe content
  }

  test("readFile returns Left(StoreError) for missing file") {
    val mock = new MockStore(Map.empty)
    val store = createStore(mock)
    val result = store.readFile(tenant("tenant-e"), "nonexistent.yaml")
    result.isLeft shouldBe true
    result.left.value shouldBe a[AclError.StoreError]
  }

  // --- listTenants ---

  test("listTenants returns parsed tenant IDs sorted alphabetically") {
    val mock = new MockStore(Map(
      "s3://test-bucket/acl/zeta/" -> TestObj("zeta/", isDir = true),
      "s3://test-bucket/acl/alpha/" -> TestObj("alpha/", isDir = true),
      "s3://test-bucket/acl/mu/" -> TestObj("mu/", isDir = true)
    ))
    val store = createStore(mock)
    val tenants = store.listTenants().value
    tenants.map(_.canonical) shouldBe List("alpha", "mu", "zeta")
  }

  test("listTenants returns empty list when no tenants exist") {
    val mock = new MockStore(Map.empty)
    val store = createStore(mock)
    store.listTenants().value shouldBe List.empty
  }

  // --- listYamlFilesWithMetadata ---

  test("listYamlFilesWithMetadata returns entries with lastModified from FsObject") {
    val timestamp = Instant.parse("2026-03-30T08:00:00Z")
    val mock = new MockStore(Map(
      "s3://test-bucket/acl/tenant-f/acl.yaml" ->
        TestObj("acl.yaml", Some("grants: []"), lastModified = Some(timestamp))
    ))
    val store = createStore(mock)
    val entries = store.listYamlFilesWithMetadata(tenant("tenant-f")).value
    entries should have size 1
    entries.head.name shouldBe "acl.yaml"
    entries.head.lastModified shouldBe Some(timestamp)
  }

  test("listYamlFilesWithMetadata returns None lastModified when metadata unavailable") {
    val mock = new MockStore(Map(
      "s3://test-bucket/acl/tenant-g/acl.yaml" ->
        TestObj("acl.yaml", Some("grants: []"))
    ))
    val store = createStore(mock)
    val entries = store.listYamlFilesWithMetadata(tenant("tenant-g")).value
    entries should have size 1
    entries.head.lastModified shouldBe None
  }

  test("listYamlFilesWithMetadata returns Left for nonexistent tenant") {
    val mock = new MockStore(Map.empty)
    val store = createStore(mock)
    val result = store.listYamlFilesWithMetadata(tenant("missing"))
    result.isLeft shouldBe true
  }

  // --- close ---

  test("close calls onClose callback") {
    val mock = new MockStore(Map.empty)
    val store = createStore(mock)
    closeCalled shouldBe false
    store.close()
    closeCalled shouldBe true
  }

  test("close is idempotent") {
    val mock = new MockStore(Map.empty)
    val store = createStore(mock)
    store.close()
    store.close()
    closeCalled shouldBe true
  }
