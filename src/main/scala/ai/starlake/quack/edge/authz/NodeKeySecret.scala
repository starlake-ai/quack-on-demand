package ai.starlake.quack.edge.authz

import com.typesafe.scalalogging.LazyLogging

import javax.sql.DataSource

/** Where the node-key secret comes from: `QOD_NODE_KEY_SECRET` when set, else the control plane's
  * own, created by the first manager that boots (table `qodstate_node_authz`, one row). Either way
  * every replica and every restart reads the same value, so a node started by one manager accepts
  * the handles of the next.
  */
object NodeKeySecret extends LazyLogging:

  val MinLength: Int = 32

  /** The secret: `configured` (trimmed) when non-empty, else [[loadOrCreate]]. `controlPlane` is by
    * name: a configured secret never touches it.
    */
  def resolve(configured: String, controlPlane: => DataSource): Either[String, String] =
    val c = configured.trim
    if c.nonEmpty then
      if c.length < MinLength then
        Left(
          s"QOD_NODE_KEY_SECRET is shorter than $MinLength characters; set a random value of " +
            s"at least $MinLength, or unset it to use the control plane's"
        )
      else Right(c)
    else Right(loadOrCreate(controlPlane))

  /** The control plane's secret, inserted first when absent. `ON CONFLICT DO NOTHING` then a read:
    * replicas booting at once all read the one row that won.
    */
  def loadOrCreate(ds: DataSource): String =
    val c = ds.getConnection
    try
      val insert = c.prepareStatement(
        "INSERT INTO qodstate_node_authz (id, key_secret) VALUES (1, ?) ON CONFLICT (id) DO NOTHING"
      )
      val created =
        try
          insert.setString(1, NodeKeys.randomSecret())
          insert.executeUpdate() == 1
        finally insert.close()
      val st = c.createStatement()
      try
        val rs = st.executeQuery("SELECT key_secret FROM qodstate_node_authz WHERE id = 1")
        if !rs.next() then throw new IllegalStateException("qodstate_node_authz holds no secret")
        logger.info(
          if created then "node-key secret: generated and stored in the control plane"
          else "node-key secret: the control plane's"
        )
        rs.getString(1)
      finally st.close()
    finally c.close()
