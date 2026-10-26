package com.nexvio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioDiagnosticsTest {
    @Test fun urlNormalizerEncodesIllegalCharactersAndPreservesEscapes() {
        assertEquals("https://example.com/a%20b%7Bq%7D/%5Bv%5D/%C3%A9%20%22", NuvioUrlNormalizer.normalize("https://example.com/a b{q}/[v]/é \""))
        assertEquals("https://example.com/a%2Fb?x=a&y=b#part", NuvioUrlNormalizer.normalize("https://example.com/a%2Fb?x=a&y=b#part"))
    }
    @Test fun safeUrlDropsQueryAndCredentials() {
        assertEquals("https://example.com:8443/api/streams", NuvioDiagnosticFormat.safeUrl("https://user:secret@example.com:8443/api/streams?token=private&key=secret"))
    }

    @Test fun safeUrlHandlesMalformedInputWithoutQuery() {
        assertEquals("not-a-url", NuvioDiagnosticFormat.safeUrl("not-a-url?secret=1"))
    }

    @Test fun requestLogFormatIncludesMetadataAndError() {
        val log = NuvioRequestLog("GET", "https://example.com/api", 503, 123, 27, "HTTP failure")
        val formatted = log.format()
        assertTrue(formatted.contains("GET https://example.com/api"))
        assertTrue(formatted.contains("503"))
        assertTrue(formatted.contains("123 ms"))
        assertTrue(formatted.contains("27 bytes"))
        assertTrue(formatted.contains("HTTP failure"))
        assertFalse(formatted.contains("?"))
    }

    @Test fun timeoutMessageIncludesRequestCountUrlAndPendingState() {
        val pending = NuvioDiagnosticFormat.timeoutMessage(7, "https://example.com/api", true)
        assertTrue(pending.contains("7 requests"))
        assertTrue(pending.contains("last: https://example.com/api"))
        assertTrue(pending.contains("request pending: true"))
        val idle = NuvioDiagnosticFormat.timeoutMessage(0, "(none)", false)
        assertTrue(idle.contains("request pending: false"))
    }
}
