package ai.starlake.quack.mail

import com.typesafe.scalalogging.LazyLogging

trait MailSender:
  /** Send a plaintext message. Right on accepted-for-delivery; Left(reason) on any failure (never
    * throws) so callers can degrade without crashing a request thread.
    */
  def send(to: String, subject: String, body: String): Either[String, Unit]

/** Dev/test sender: logs the recipient + subject and a redacted body length. NEVER a production
  * fallback when lockout is enabled (Main wires SMTP there).
  */
final class LogMailSender extends MailSender, LazyLogging:
  def send(to: String, subject: String, body: String): Either[String, Unit] =
    logger.info(s"[LogMailSender] to=$to subject='$subject' bodyChars=${body.length}")
    Right(())
