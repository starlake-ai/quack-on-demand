package ai.starlake.quack.mail

import ai.starlake.quack.SmtpConfig
import com.typesafe.scalalogging.LazyLogging
import jakarta.mail.internet.{InternetAddress, MimeMessage}
import jakarta.mail.{Address, Authenticator, Message, PasswordAuthentication, Session, Transport}

import java.util.Properties

/** Real SMTP sender backing account-lockout reset emails. `send` never throws -- every exception
  * from session setup, message building, or `Transport.send` is caught and folded into
  * `Left(reason)` so a request thread can't be killed by a relay outage.
  */
final class SmtpMailSender(cfg: SmtpConfig) extends MailSender, LazyLogging:
  def send(to: String, subject: String, body: String): Either[String, Unit] =
    try
      val props = new Properties()
      props.put("mail.smtp.host", cfg.host.getOrElse(""))
      props.put("mail.smtp.port", cfg.port.toString)
      props.put("mail.smtp.starttls.enable", cfg.starttls.toString)
      props.put("mail.smtp.auth", cfg.user.isDefined.toString)

      val session = cfg.user match
        case Some(u) =>
          val authenticator = new Authenticator:
            override def getPasswordAuthentication: PasswordAuthentication =
              new PasswordAuthentication(u, cfg.password.getOrElse(""))
          Session.getInstance(props, authenticator)
        case None =>
          Session.getInstance(props)

      val message = new MimeMessage(session)
      message.setFrom(new InternetAddress(cfg.from))
      message.setRecipients(
        Message.RecipientType.TO,
        InternetAddress.parse(to).asInstanceOf[Array[Address]]
      )
      message.setSubject(subject)
      message.setText(body)

      Transport.send(message)
      Right(())
    catch
      case e: Exception =>
        val reason = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        logger.warn(s"[SmtpMailSender] send to=$to failed: $reason")
        Left(reason)
