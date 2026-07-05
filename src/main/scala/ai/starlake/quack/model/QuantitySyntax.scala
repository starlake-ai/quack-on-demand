package ai.starlake.quack.model

import io.fabric8.kubernetes.api.model.{Pod, Quantity}
import io.fabric8.kubernetes.client.utils.Serialization
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Validators for Kubernetes pod-sizing inputs. The regex pre-filter is the trust boundary; fabric8
  * Quantity parse provides additional validation.
  */
object QuantitySyntax:

  // CPU: plain integer, decimal, or millicore (e.g. "2", "1.5", "500m")
  private val cpuRe = raw"^\d+(\.\d+)?m?$$".r
  // Memory: integer or decimal with SI / binary suffix
  private val memRe = raw"^\d+(\.\d+)?(Ki|Mi|Gi|Ti|k|M|G|T)?$$".r

  def validQuantity(s: String): Boolean =
    s.nonEmpty &&
      (cpuRe.matches(s) || memRe.matches(s)) &&
      Try(new Quantity(s)).map(_.getAmount != null).getOrElse(false)

  def validPodTemplate(yaml: String): Either[String, Unit] =
    Try(Serialization.unmarshal(yaml, classOf[Pod])).toEither.left
      .map(e => s"not a valid Pod manifest: ${e.getMessage}")
      .flatMap { pod =>
        val names =
          Option(pod.getSpec)
            .map(_.getContainers.asScala.map(_.getName).toList)
            .getOrElse(Nil)
        if names.contains("quack") then Right(())
        else Left("pod template must define a container named 'quack'")
      }
