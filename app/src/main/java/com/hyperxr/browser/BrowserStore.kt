package com.hyperxr.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bookmarks and history of the XR browser, kept in a small JSON file in app storage. */
class BrowserStore(private val file: File) {
    data class Entry(val title: String, val url: String, val time: Long = System.currentTimeMillis())

    val bookmarks = ArrayList<Entry>()
    val history = ArrayList<Entry>()

    init { load() }

    @Synchronized fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    @Synchronized fun toggleBookmark(title: String, url: String): Boolean {
        val had = bookmarks.removeAll { it.url == url }
        if (!had) bookmarks.add(0, Entry(title.ifBlank { url }, url))
        save()
        return !had
    }

    @Synchronized fun visit(title: String, url: String) {
        if (!url.startsWith("http")) return
        history.removeAll { it.url == url }
        history.add(0, Entry(title.ifBlank { url }, url))
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        save()
    }

    @Synchronized fun updateTitle(url: String, title: String) {
        val i = history.indexOfFirst { it.url == url }
        if (i >= 0 && title.isNotBlank()) { history[i] = history[i].copy(title = title); save() }
    }

    @Synchronized fun clearHistory() { history.clear(); save() }

    private fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            read(o.optJSONArray("bookmarks"), bookmarks)
            read(o.optJSONArray("history"), history)
        } catch (_: Throwable) { /* corrupt file: start empty */ }
    }

    private fun read(a: JSONArray?, out: MutableList<Entry>) {
        if (a == null) return
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            out += Entry(e.optString("title"), e.optString("url"), e.optLong("time"))
        }
    }

    private fun save() {
        val o = JSONObject()
        o.put("bookmarks", JSONArray().apply { bookmarks.forEach { put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time)) } })
        o.put("history", JSONArray().apply { history.forEach { put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time)) } })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    companion object {
        const val MAX_HISTORY = 300

    }
}
