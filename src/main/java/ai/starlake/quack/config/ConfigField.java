package ai.starlake.quack.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a config case-class field as a runtime-tunable knob. Powers
 * the manager UI's Config page (one row per annotated field) and the
 * `/api/config/server` endpoint. Defined in Java with RUNTIME
 * retention so the Scala-side reflector can read it via
 * {@code Class#getDeclaredField(name).getAnnotation(ConfigField.class)}.
 * <p>
 * In Scala 3 case classes, the annotation must be meta-targeted at
 * the synthetic field with {@code @scala.annotation.meta.field}:
 *
 * <pre>{@code
 * import scala.annotation.meta.field
 * import ai.starlake.quack.config.ConfigField
 *
 * final case class FlightConfig(
 *   @field @ConfigField(envVar = "PROXY_HOST", description = "...")
 *   host: String,
 *   ...
 * )
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigField {
    String envVar();
    String description();
    boolean sensitive() default false;
}