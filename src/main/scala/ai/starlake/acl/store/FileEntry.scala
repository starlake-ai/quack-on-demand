package ai.starlake.acl.store

import java.time.Instant

/** Metadata for an ACL file in storage.
  *
  * @param name
  *   File name (e.g., "grants.yaml")
  * @param lastModified
  *   Last modification timestamp, if available from the storage backend
  */
case class FileEntry(name: String, lastModified: Option[Instant])
