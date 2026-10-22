package com.hyperxr

import com.hyperxr.browser.UrlInput
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlInputTest {
    @Test fun resolvesUrlsHostsAndSearches() {
        assertEquals("https://example.com/a", UrlInput.resolve("https://example.com/a"))
        assertEquals("https://youtube.com", UrlInput.resolve("youtube.com"))
        assertEquals("https://m.twitch.tv/x?y=1", UrlInput.resolve(" m.twitch.tv/x?y=1 "))
        assertEquals("http://192.168.0.10:8080/", UrlInput.resolve("192.168.0.10:8080/"))
        assertEquals("http://localhost:3000", UrlInput.resolve("localhost:3000"))
        assertEquals(UrlInput.DEFAULT_SEARCH + "gatos+fofos", UrlInput.resolve("gatos fofos"))
        assertEquals(UrlInput.DEFAULT_SEARCH + "hyperxr", UrlInput.resolve("hyperxr"))
        assertEquals("about:blank", UrlInput.resolve("  "))
    }

    @Test fun displaysHost() {
        assertEquals("youtube.com", UrlInput.display("https://www.youtube.com/watch?v=1"))
        assertEquals("about:blank", UrlInput.display("about:blank"))
    }
}
