package ai.starlake.quack.edge.auth

import com.nimbusds.jose.crypto.{MACSigner, RSASSASigner}
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSObject, Payload}

import java.util.concurrent.atomic.AtomicReference

/** Signed tokens whose claims nest far deeper than any real token does, and the thread to try them
  * on.
  *
  * A claim nested thousands of levels deep passes the signature check and then overflows the stack
  * of whatever prints or walks it, and a `StackOverflowError` is fatal to cats-effect: one escaping
  * under the manager's runtime halts the whole process. So a spec never sends such a token through
  * a cats-effect runtime or through a harness. It goes through [[PlainThread.run]], where a
  * regression fails the case (the thread reports the error) and the test JVM goes on.
  */
object NestedClaims:

  /** The depth of the reproduction: an 80 KB token, overflowing a 1 MiB stack. */
  val Depth: Int = 30000

  /** A JSON array nested `depth` levels deep. */
  def array(depth: Int): String = "[" * depth + "]" * depth

  /** The claims of a token for `issuer` and `audience`, expiring in an hour, with one more claim
    * `name` holding an array nested `depth` levels deep.
    */
  def claims(issuer: String, audience: String, name: String, depth: Int): String =
    val exp = System.currentTimeMillis() / 1000 + 3600
    s"""{"iss":"$issuer","aud":"$audience","sub":"nested-user","exp":$exp,"$name":${array(
        depth
      )}}"""

  /** An RS256 token over `payloadJson` as it stands, signed with `key`. */
  def rs256(key: RSAKey, payloadJson: String): String =
    val header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).build()
    val jws    = new JWSObject(header, new Payload(payloadJson))
    jws.sign(new RSASSASigner(key))
    jws.serialize()

  /** An HS256 token over `payloadJson` as it stands, signed with `secret`. */
  def hs256(secret: String, payloadJson: String): String =
    val jws = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(payloadJson))
    jws.sign(new MACSigner(secret))
    jws.serialize()

/** A plain thread to try a call on, never a cats-effect one: whatever the call throws, even a
  * `StackOverflowError`, ends that thread and is reported as the answer, and the test JVM goes on.
  */
object PlainThread:

  /** What `body` answers on a new thread of `stackKiB` KiB of stack: `Right` with its answer, or
    * `Left` with the class of whatever it threw, or the words "still running" when the thread has
    * not ended in `waitSec` seconds. A stack as small as the default makes a depth of
    * [[NestedClaims.Depth]] overflow it on any platform, whatever the JIT does to the size of a
    * frame.
    */
  def run[A](stackKiB: Int = 512, waitSec: Int = 60)(body: => A): Either[String, A] =
    val out    = new AtomicReference[Either[String, A]](Left("the thread ended without an answer"))
    val thread = new Thread(
      null,
      () =>
        out.set(
          try Right(body)
          catch case e: Throwable => Left(e.getClass.getName)
        ),
      "plain-thread",
      stackKiB.toLong * 1024L
    )
    thread.setDaemon(true)
    thread.start()
    thread.join(waitSec * 1000L)
    if thread.isAlive then Left(s"still running after $waitSec s") else out.get()
