package com.hyperxr.browser

/** Address-bar logic shared by the XR browser (pure JVM so it is unit tested). */
object UrlInput {
    const val DEFAULT_SEARCH = "https://duckduckgo.com/?q="

    /** Turns what was typed into a URL: a URL or host as is, anything else becomes a search. */
    fun resolve(input: String, searchUrl: String = DEFAULT_SEARCH): String {
        val t = input.trim()
        if (t.isEmpty()) return "about:blank"
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t) || t.startsWith("about:")) return t
        val looksLikeHost = !t.contains(' ') &&
            (Regex("^[^\\s/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(t) || Regex("^localhost(:\\d+)?(/.*)?$").matches(t) ||
                Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(t))
        if (looksLikeHost) return (if (t.startsWith("localhost") || t[0].isDigit()) "http://" else "https://") + t
        return searchUrl + java.net.URLEncoder.encode(t, "UTF-8")
    }

    /** Short form for the address bar and tab chips: host without "www.". */
    fun display(url: String): String {
        val host = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)").find(url)?.groupValues?.get(1) ?: return url
        return host.removePrefix("www.")
    }
}
