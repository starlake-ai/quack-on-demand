package ai.starlake.quack.ondemand.api

import ai.starlake.quack.config.ConfigField

import java.lang.reflect.Constructor
import scala.collection.mutable

/** Walks the typed manager + edge config case classes via reflection to produce a single flat list
  * of every `@ConfigField`-annotated scalar, in declaration order. Single source of truth for the
  * admin UI's Config page; adding a new env var means adding one annotated parameter on the
  * matching case class -- nothing else needs to change here.
  *
  * The reflector reads constructor parameter annotations rather than field annotations: in Scala 3
  * case classes, `@ConfigField(...)` on a constructor parameter is emitted as a
  * `RuntimeVisibleParameterAnnotations` attribute on the primary constructor, not on the synthetic
  * `private final` field. Pairing the parameter annotations with the declared fields (which carry
  * the field names and types) is the most reliable way to recover
  * `(name, envVar, description, sensitive)` tuples at runtime.
  */
final case class ConfigEntry(
    path: String,
    envVar: String,
    description: String,
    sensitive: Boolean
)

object ConfigRegistry:

  /** Root case-class types to traverse, paired with their HOCON path prefix. Order here drives the
    * registry's output order and so the UI's section order.
    */
  def rootsFor(
      managerCls: Class[?],
      flightCls: Class[?],
      authCls: Class[?],
      aclCls: Class[?],
      validationCls: Class[?],
      metricsCls: Class[?]
  ): List[(String, Class[?])] = List(
    "quack-on-demand"            -> managerCls,
    "quack-on-demand.metrics"    -> metricsCls,
    "quack-flightsql"            -> flightCls,
    "quack-flightsql.auth"       -> authCls,
    "quack-flightsql.validation" -> validationCls,
    "quack-flightsql.acl"        -> aclCls
  )

  /** Build the full registry by reflecting through `roots`. */
  def collect(roots: List[(String, Class[?])]): List[ConfigEntry] =
    val out = mutable.ListBuffer.empty[ConfigEntry]
    roots.foreach { case (prefix, cls) => walk(prefix, cls, out) }
    out.toList

  /** Recursively walk `cls`'s declared fields, in declaration order, pairing each with the matching
    * constructor-parameter annotation.
    */
  private def walk(prefix: String, cls: Class[?], out: mutable.ListBuffer[ConfigEntry]): Unit =
    val fields = cls.getDeclaredFields.filterNot { f =>
      java.lang.reflect.Modifier.isStatic(f.getModifiers) || f.isSynthetic
    }
    val paramAnns = primaryConstructorParamAnnotations(cls, fields.length)
    fields.zipWithIndex.foreach { case (f, i) =>
      val path = s"$prefix.${f.getName}"
      val ann  =
        if i < paramAnns.length then paramAnns(i).collectFirst { case a: ConfigField => a }
        else None
      ann match
        case Some(a) =>
          out += ConfigEntry(path, a.envVar(), a.description(), a.sensitive())
        case None =>
          if isCaseClass(f.getType) then walk(path, f.getType, out)
          // else: Map, primitive without annotation - intentionally skipped.
    }

  /** Locate the primary constructor (the one whose arity matches the case class's field count) and
    * return its parameter annotation lists in declaration order. Falls back to the first
    * constructor if no perfect arity match exists.
    */
  private def primaryConstructorParamAnnotations(
      cls: Class[?],
      fieldCount: Int
  ): Array[Array[java.lang.annotation.Annotation]] =
    val ctors = cls.getDeclaredConstructors
    if ctors.isEmpty then Array.empty
    else
      val primary: Constructor[?] =
        ctors.find(_.getParameterCount == fieldCount).getOrElse(ctors.maxBy(_.getParameterCount))
      primary.getParameterAnnotations

  /** True iff `cls` is one of our own case classes that we should recurse into. Excludes Option /
    * List / Map and other Product-implementing collections by also requiring the class name to not
    * be in the scala.* / java.* packages.
    */
  private def isCaseClass(cls: Class[?]): Boolean =
    classOf[Product].isAssignableFrom(cls) &&
      !cls.getName.startsWith("scala.") &&
      !cls.getName.startsWith("java.")
