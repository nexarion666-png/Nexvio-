package com.nexvio.app
import org.junit.Assert.*
import org.junit.Test
class AddonModelsTest {
 @Test fun resourceCapabilitiesHonorTypesAndPrefixes() {
  val m=AddonManifest("example","Example","1.0",resources=listOf(AddonResource("stream",listOf("movie"),listOf("tt"))),types=listOf("movie","series"))
  assertTrue(m.supportsResource("stream","movie","tt123"));assertFalse(m.supportsResource("stream","series","tt123"))
  assertFalse(m.supportsResource("stream","movie","tmdb:123"));assertFalse(m.supportsResource("catalog","movie"))
 }
 @Test fun manifestUrlsRejectUnsafeSchemesCredentialsAndQueries() {
  assertEquals("https://example.com/manifest.json",normalizeManifestUrl("HTTPS://Example.com:443/manifest.json"))
  assertTrue(runCatching { normalizeManifestUrl("file:///manifest.json") }.isFailure)
  assertTrue(runCatching { normalizeManifestUrl("https://user:pass@example.com/manifest.json") }.isFailure)
  assertTrue(runCatching { normalizeManifestUrl("https://example.com/manifest.json?token=x") }.isFailure)
 }
}
