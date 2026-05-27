package ai.starlake.acl.policy

import ai.starlake.acl.AclError
import cats.data.Validated
import org.scalatest.funsuite.AnyFunSuite

class EnvVarResolverTest extends AnyFunSuite {

  private def env(vars: (String, String)*): String => Option[String] =
    vars.toMap.get

  test("no variables in input returns input unchanged") {
    val result = EnvVarResolver.resolve("grants:\n  - target: mydb", env())
    assert(result == Validated.Valid("grants:\n  - target: mydb"))
  }

  test("single variable resolved correctly") {
    val result = EnvVarResolver.resolve("target: ${DB_NAME}.public.orders", env("DB_NAME" -> "prod"))
    assert(result == Validated.Valid("target: prod.public.orders"))
  }

  test("multiple variables resolved correctly") {
    val result = EnvVarResolver.resolve(
      "target: ${DB}.${SCHEMA}.orders",
      env("DB" -> "mydb", "SCHEMA" -> "public")
    )
    assert(result == Validated.Valid("target: mydb.public.orders"))
  }

  test("variable in both target and principal positions") {
    val input = "target: ${DB}.public.orders\nprincipal: user:${USER}"
    val result = EnvVarResolver.resolve(input, env("DB" -> "mydb", "USER" -> "alice"))
    assert(result == Validated.Valid("target: mydb.public.orders\nprincipal: user:alice"))
  }

  test("unresolved single variable returns InvalidNel with UnresolvedVariable") {
    val result = EnvVarResolver.resolve("target: ${MISSING}.public.orders", env())
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList == List(AclError.UnresolvedVariable("MISSING")))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("multiple unresolved variables returns all errors") {
    val result = EnvVarResolver.resolve("${A}.${B}.${C}", env())
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.size == 3)
        assert(errors.toList.contains(AclError.UnresolvedVariable("A")))
        assert(errors.toList.contains(AclError.UnresolvedVariable("B")))
        assert(errors.toList.contains(AclError.UnresolvedVariable("C")))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("mix of resolved and unresolved returns only unresolved as errors") {
    val result = EnvVarResolver.resolve("${GOOD}.${BAD}", env("GOOD" -> "ok"))
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList == List(AclError.UnresolvedVariable("BAD")))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("replacement value containing $ is handled safely") {
    val result = EnvVarResolver.resolve("value: ${PRICE}", env("PRICE" -> "$100"))
    assert(result == Validated.Valid("value: $100"))
  }

  test("replacement value containing backslash is handled safely") {
    val result = EnvVarResolver.resolve("path: ${DIR}", env("DIR" -> "C:\\Users\\test"))
    assert(result == Validated.Valid("path: C:\\Users\\test"))
  }

  test("empty input returns empty string") {
    val result = EnvVarResolver.resolve("", env())
    assert(result == Validated.Valid(""))
  }

  test("single char variable name resolves") {
    val result = EnvVarResolver.resolve("${A}", env("A" -> "x"))
    assert(result == Validated.Valid("x"))
  }

  test("variable with underscores and digits resolves") {
    val result = EnvVarResolver.resolve("${A_B_123}", env("A_B_123" -> "val"))
    assert(result == Validated.Valid("val"))
  }

  test("non-variable dollar signs are left untouched - $notvar") {
    val result = EnvVarResolver.resolve("$notvar stays", env())
    assert(result == Validated.Valid("$notvar stays"))
  }

  test("non-variable dollar signs are left untouched - empty braces") {
    val result = EnvVarResolver.resolve("${} stays", env())
    assert(result == Validated.Valid("${} stays"))
  }

  test("duplicate variable references produce single error") {
    val result = EnvVarResolver.resolve("${X} and ${X}", env())
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList == List(AclError.UnresolvedVariable("X")))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }
}
