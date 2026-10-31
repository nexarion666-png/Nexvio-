package com.nexvio.app

import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    @Test fun parsesMultipleChannelsAndMetadata() {
        val result = M3uParser.parse("""#EXTM3U
#EXTINF:-1 tvg-id="one" tvg-name="One News" tvg-logo="https://example.com/a.png" group-title="News",One News
https://example.com/live/one.m3u8
#EXTINF:-1 tvg-id="two" group-title="Sports",Sports: Two!
https://example.com/two
""")
        assertEquals(2, result.channels.size)
        assertEquals("One News", result.channels[0].name)
        assertEquals("one", result.channels[0].tvgId)
        assertEquals("https://example.com/a.png", result.channels[0].logoUrl)
        assertEquals("News", result.channels[0].category)
        assertEquals("Sports: Two!", result.channels[1].name)
    }

    @Test fun supportsNamesWithSpacesAndPunctuation() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1,Channel: One & Two!\nhttps://example.com/a")
        assertEquals("Channel: One & Two!", p.channels.single().name)
    }

    @Test fun optionalAttributesCanBeMissing() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1,Plain Channel\nhttps://example.com/a")
        assertNull(p.channels.single().logoUrl)
        assertNull(p.channels.single().category)
    }

    @Test fun categoriesAreKeptSeparately() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1 group-title=\"News\",A\nhttps://example.com/a\n#EXTINF:-1 group-title=\"Sports\",B\nhttps://example.com/b")
        assertEquals(listOf("News", "Sports"), p.channels.map { it.category })
    }

    @Test fun emptyPlaylistReportsError() {
        assertNotNull(M3uParser.parse("").error)
        assertTrue(M3uParser.parse("#EXTM3U").channels.isEmpty())
    }

    @Test fun malformedEntriesDoNotCrash() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1,Bad\nnot-a-url\n#EXTINF:-1,Good\nhttps://example.com/good")
        assertEquals(1, p.channels.size)
        assertEquals("Good", p.channels.single().name)
    }

    @Test fun supportsCrLfAndLf() {
        val crlf = M3uParser.parse("#EXTM3U\r\n#EXTINF:-1,One\r\nhttps://example.com/one\r\n")
        val lf = M3uParser.parse("#EXTM3U\n#EXTINF:-1,One\nhttps://example.com/one\n")
        assertEquals(crlf.channels.single().streamUrl, lf.channels.single().streamUrl)
    }

    @Test fun queryParametersRemainUnchanged() {
        val url = "https://example.com/live.m3u8?token=a%2Fb&x=1"
        assertEquals(url, M3uParser.parse("#EXTM3U\n#EXTINF:-1,One\n$url").channels.single().streamUrl)
    }

    @Test fun missingStreamUrlIsIgnored() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1,One\n#EXTINF:-1,Two\nhttps://example.com/two")
        assertEquals(1, p.channels.size)
        assertEquals("Two", p.channels.single().name)
    }

    @Test fun quotedAttributeValuesCanContainSpacesAndPunctuation() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-name=\"A Channel, HD\" tvg-logo=\"https://example.com/logo?size=2&v=1\" group-title=\"News & Talk\",Label\nhttps://example.com/a")
        assertEquals("A Channel, HD", p.channels.single().name)
        assertEquals("News & Talk", p.channels.single().category)
        assertEquals("https://example.com/logo?size=2&v=1", p.channels.single().logoUrl)
    }
}
