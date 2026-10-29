package com.hyperxr.i18n

/**
 * HyperMagic's texts are written in Portuguese in the code; they are translated when they are
 * drawn. A translation maps each Portuguese text (keys.json, made by tools/i18n_extract.py) to
 * the language's text; texts built at run time match templates with numbered holes
 * ("Página {0} de {1}" -> "Page {0} of {1}"), and joined texts ("Grátis • Baixar") are
 * translated part by part. Anything unknown stays as it is.
 */
object I18n {
    /** Language codes offered, and their names in their own language. */
    val LANGUAGES = listOf("pt" to "Português", "en" to "English", "es" to "Español", "ru" to "Русский")

    @Volatile var language = "pt"; private set
    /** Changes every time the language changes (panels redraw when it does). */
    @Volatile var version = 0; private set

    private var exact: Map<String, String> = emptyMap()
    private class Pattern(val regex: Regex, val out: String)
    private var byPrefix: Map<String, List<Pattern>> = emptyMap()
    private var loose: List<Pattern> = emptyList()
    private val cache = HashMap<String, String>()
    private val SEPARATORS = listOf("\n", "  ·  ", " • ", " · ", " — ", ": ", " | ", "  ")
    private val HOLE = Regex("""\{(\d+)\}""")

    /** Switches to [code] with its [table] (Portuguese text -> translation); "pt" or an empty table turns translation off. */
    @Synchronized
    fun use(code: String, table: Map<String, String>) {
        language = code
        val ex = HashMap<String, String>()
        val pre = HashMap<String, MutableList<Pattern>>()
        val lo = ArrayList<Pattern>()
        if (code != "pt") for ((k, v) in table) {
            if (v.isEmpty()) continue
            if (!HOLE.containsMatchIn(k)) { ex[k] = v; continue }
            val parts = k.split(HOLE)
            val order = HOLE.findAll(k).map { it.groupValues[1].toInt() }.toList()
            // a template that is almost only holes ("{0} de {1}") would turn names into nonsense
            // ("Maria of Souza"): its holes take numbers only
            val words = parts.joinToString("").count { it.isLetter() }
            val hole = if (words < 4) "([-+]?[0-9][0-9.,:%/ ]*)" else null
            val sb = StringBuilder("^")
            for ((i, p) in parts.withIndex()) {
                sb.append(Regex.escape(p))
                if (i < order.size) sb.append(hole ?: if (i == order.size - 1 && parts.last().isEmpty()) "(.+)" else "(.+?)")
            }
            sb.append("$")
            // the translation refers to the holes by their number; matched groups come in key order
            var out = v
            order.forEachIndexed { gi, n -> out = out.replace("{$n}", "\u0000$gi\u0000") }
            val pat = Pattern(Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL), out)
            val head = parts.first()
            if (head.length >= 2) pre.getOrPut(head.take(2)) { ArrayList() } += pat else lo += pat
        }
        // longer templates first: the most specific wins
        exact = ex
        byPrefix = pre.mapValues { (_, l) -> l.sortedByDescending { it.regex.pattern.length } }
        loose = lo.sortedByDescending { it.regex.pattern.length }
        cache.clear()
        version++
    }

    /** [s] in the current language. */
    fun t(s: String): String {
        if (language == "pt" || s.isEmpty()) return s
        synchronized(this) {
            cache[s]?.let { return it }
            val r = translate(s, 0)
            if (cache.size > 6000) cache.clear()
            cache[s] = r
            return r
        }
    }

    private fun translate(s: String, depth: Int): String {
        exact[s]?.let { return it }
        // keep spaces and punctuation around a known text
        val core = s.trim()
        if (core.isEmpty()) return s
        if (core.length != s.length) {
            val inner = translate(core, depth + 1)
            if (inner !== core && inner != core) return s.substring(0, s.indexOf(core)) + inner + s.substring(s.indexOf(core) + core.length)
        }
        matchPattern(core)?.let { return it }
        for (end in listOf("…", ":", ".", "!", "?")) if (core.endsWith(end) && core.length > end.length) {
            exact[core.dropLast(end.length)]?.let { return it + end }
        }
        // joined texts: part by part
        if (depth < 3) for (sep in SEPARATORS) if (s.contains(sep)) {
            val parts = s.split(sep)
            val out = parts.map { translate(it, depth + 1) }
            if (out != parts) return out.joinToString(sep)
        }
        return s
    }

    private fun matchPattern(s: String): String? {
        val cands = (if (s.length >= 2) byPrefix[s.take(2)].orEmpty() else emptyList()) + loose
        for (p in cands) {
            val m = p.regex.matchEntire(s) ?: continue
            var out = p.out
            for (gi in 0 until m.groupValues.size - 1) {
                // the filled-in parts can be texts too ("Abrir {0}" with a game's name)
                out = out.replace("\u0000$gi\u0000", translate(m.groupValues[gi + 1], 2))
            }
            return out
        }
        return null
    }
}
