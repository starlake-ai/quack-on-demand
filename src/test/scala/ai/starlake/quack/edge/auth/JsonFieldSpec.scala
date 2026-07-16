package ai.starlake.quack.edge.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins [[JsonField]], the shared regex JSON-field extractor that replaced four identical private
  * copies across the auth package (token responses, OIDC discovery, service-account keys, Directory
  * API responses). The semantics are deliberately those of the historical copies: match the field
  * name at ANY depth, take the raw (un-unescaped) value, never handle escaped quotes.
  */
class JsonFieldSpec extends AnyFlatSpec with Matchers:

  "JsonField.first" should "extract a top-level string field" in {
    JsonField.first("""{"access_token":"abc123","expires_in":300}""", "access_token") shouldBe
      Some("abc123")
  }

  it should "tolerate whitespace around the colon" in {
    JsonField.first("""{"id_token" :  "xyz"}""", "id_token") shouldBe Some("xyz")
  }

  it should "match a nested field (historical any-depth semantics)" in {
    JsonField.first("""{"outer":{"client_email":"svc@x.iam"}}""", "client_email") shouldBe
      Some("svc@x.iam")
  }

  it should "return the raw value without unescaping (private_key keeps literal backslash-n)" in {
    // Triple quotes: the JSON value contains the two characters backslash + n,
    // and the extractor must return them raw (callers unescape themselves).
    JsonField.first("""{"private_key":"line1\nline2"}""", "private_key") shouldBe
      Some("line1\\nline2")
  }

  it should "return None for a missing field" in {
    JsonField.first("""{"a":"b"}""", "refresh_token") shouldBe None
  }

  it should "return None for a non-string value" in {
    JsonField.first("""{"expires_in":300}""", "expires_in") shouldBe None
  }

  "JsonField.all" should "extract every occurrence of the field" in {
    val json = """{"groups":[{"email":"A@x.io"},{"email":"b@y.io"}],"email":"c@z.io"}"""
    JsonField.all(json, "email") shouldBe List("A@x.io", "b@y.io", "c@z.io")
  }

  it should "return an empty list when the field never occurs" in {
    JsonField.all("""{"a":"b"}""", "email") shouldBe Nil
  }
