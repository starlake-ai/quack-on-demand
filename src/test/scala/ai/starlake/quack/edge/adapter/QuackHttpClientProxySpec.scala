package ai.starlake.quack.edge.adapter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class QuackHttpClientProxySpec extends AnyFlatSpec with Matchers:

  import QuackHttpClient.{normaliseProxyUrl, proxyHostPortFrom}

  "normaliseProxyUrl" should "strip http:// scheme" in:
    normaliseProxyUrl("http://proxy.corp:3128") shouldBe "proxy.corp:3128"

  it should "strip https:// scheme" in:
    normaliseProxyUrl("https://proxy.corp:3128") shouldBe "proxy.corp:3128"

  it should "strip trailing slash" in:
    normaliseProxyUrl("http://proxy.corp:3128/") shouldBe "proxy.corp:3128"

  it should "pass schemeless host:port through unchanged" in:
    normaliseProxyUrl("proxy.corp:3128") shouldBe "proxy.corp:3128"

  "proxyHostPortFrom" should "return None on empty env" in:
    proxyHostPortFrom(Map.empty) shouldBe None

  it should "ignore unrelated env vars" in:
    proxyHostPortFrom(Map("PATH" -> "/usr/bin", "HOME" -> "/home/u")) shouldBe None

  it should "treat blank/whitespace values as unset" in:
    proxyHostPortFrom(Map("HTTP_PROXY" -> "   ", "HTTPS_PROXY" -> "")) shouldBe None

  it should "prefer HTTP_PROXY over HTTPS_PROXY" in:
    val env = Map(
      "HTTP_PROXY"  -> "http://primary:3128",
      "HTTPS_PROXY" -> "http://secondary:3128"
    )
    proxyHostPortFrom(env) shouldBe Some("primary:3128")

  it should "fall back to lowercase variants" in:
    proxyHostPortFrom(Map("http_proxy" -> "http://lower:3128")) shouldBe Some("lower:3128")

  it should "fall back to HTTPS_PROXY when HTTP_PROXY is blank" in:
    val env = Map(
      "HTTP_PROXY"  -> "",
      "HTTPS_PROXY" -> "http://secondary:3128"
    )
    proxyHostPortFrom(env) shouldBe Some("secondary:3128")