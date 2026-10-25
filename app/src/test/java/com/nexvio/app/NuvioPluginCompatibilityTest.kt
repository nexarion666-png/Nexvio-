package com.nexvio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioPluginCompatibilityTest {
    @Test
    fun parsesNuvioScrapersAndProviderMetadata() {
        val manifest = """
            {
              "name": "Example Nuvio Repo",
              "version": "1.0.0",
              "scrapers": [{
                "id": "sample",
                "name": "Sample Provider",
                "description": "Test provider",
                "version": "2.1.0",
                "filename": "providers/sample.js",
                "enabled": false,
                "hasSettings": true,
                "supportedTypes": ["movie", "tv"],
                "formats": ["m3u8", "mp4"],
                "contentLanguage": ["en", "hi"],
                "author": "Example",
                "limited": true,
                "notes": "Sample note",
                "disabledPlatforms": ["ios"],
                "supportsExternalPlayer": false
              }]
            }
        """.trimIndent()

        val parsed = NuvioPluginManifestParser.parse(
            manifest,
            "https://example.com/repo/manifest.json"
        )

        assertEquals("Example Nuvio Repo", parsed.name)
        assertEquals("1.0.0", parsed.version)
        assertEquals(1, parsed.providers.size)
        val provider = parsed.providers.single()
        assertEquals("sample", provider.id)
        assertEquals("https://example.com/repo/providers/sample.js", provider.fileUrl)
        assertFalse(provider.enabledByDefault)
        assertTrue(provider.hasSettings)
        assertEquals(listOf("movie", "tv"), provider.supportedTypes)
        assertEquals(listOf("m3u8", "mp4"), provider.formats)
        assertEquals(listOf("en", "hi"), provider.contentLanguages)
        assertEquals("Example", provider.author)
        assertTrue(provider.limited)
        assertEquals("Sample note", provider.notes)
        assertEquals(listOf("ios"), provider.disabledPlatforms)
        assertEquals(false, provider.supportsExternalPlayer)
    }

    @Test
    fun rejectsStremioAddonManifestAsNuvioPluginRepository() {
        val manifest = """{"id":"org.example.addon","name":"Stremio Add-on","version":"1.0.0","resources":["stream"]}"""
        val error = runCatching {
            NuvioPluginManifestParser.parse(manifest, "https://example.com/manifest.json")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException || error is IllegalArgumentException)
    }

    @Test
    fun rejectsInsecureManifestUrl() {
        val manifest = """{"name":"Repo","version":"1","scrapers":[{"id":"a","name":"A","filename":"a.js"}]}"""
        assertTrue(runCatching {
            NuvioPluginManifestParser.parse(manifest, "http://example.com/manifest.json")
        }.isFailure)
    }

    @Test
    fun missingOptionalMetadataUsesSafeDefaults() {
        val manifest = """{"name":"Repo","version":"1","scrapers":[{"id":"a","name":"A","filename":"a.js"}]}"""
        val provider = NuvioPluginManifestParser.parse(
            manifest,
            "https://example.com/manifest.json"
        ).providers.single()
        assertTrue(provider.enabledByDefault)
        assertFalse(provider.hasSettings)
        assertTrue(provider.supportedTypes.isEmpty())
        assertNull(provider.supportsExternalPlayer)
    }
}
