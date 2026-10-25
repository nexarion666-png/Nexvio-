package com.nexvio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginUrlPolicyTest {
    @Test fun acceptsAndNormalizesHttpsManifestUrls() {
        assertEquals("https://example.com/plugins/manifest.json", PluginUrlPolicy.normalizeManifestUrl(" https://example.com/plugins/manifest.json "))
    }

    @Test fun rejectsInsecureOrMalformedManifestUrls() {
        assertNull(PluginUrlPolicy.normalizeManifestUrl("http://example.com/manifest.json"))
        assertNull(PluginUrlPolicy.normalizeManifestUrl("file:///sdcard/manifest.json"))
        assertNull(PluginUrlPolicy.normalizeManifestUrl("https://user:pass@example.com/manifest.json"))
        assertNull(PluginUrlPolicy.normalizeManifestUrl("not a url"))
    }

    @Test fun resolvesRelativeProviderFilesAgainstManifestDirectory() {
        assertEquals("https://example.com/plugins/provider.js", PluginUrlPolicy.resolveProviderUrl("https://example.com/plugins/manifest.json", "provider.js"))
    }

    @Test fun rejectsProviderUrlsThatEscapeToInsecureSchemes() {
        assertNull(PluginUrlPolicy.resolveProviderUrl("https://example.com/plugins/manifest.json", "file:///sdcard/provider.js"))
        assertNull(PluginUrlPolicy.resolveProviderUrl("https://example.com/plugins/manifest.json", "http://example.com/provider.js"))
    }
}
