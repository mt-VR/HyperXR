package com.hyperxr.i18n

import android.content.Context
import org.json.JSONObject

/** Loads the chosen language's texts (assets/i18n/<code>.json) into [I18n]. */
object Translations {
    /** The language that [setting] means: itself, or the phone's language when it is empty. */
    fun resolve(setting: String): String {
        if (setting.isNotEmpty()) return setting
        val phone = java.util.Locale.getDefault().language
        return if (I18n.LANGUAGES.any { it.first == phone }) phone else "pt"
    }

    @Synchronized
    fun apply(context: Context, setting: String) {
        val code = resolve(setting)
        if (code == I18n.language && I18n.version > 0) return
        val table = HashMap<String, String>()
        if (code != "pt") runCatching {
            val o = JSONObject(context.assets.open("i18n/$code.json").bufferedReader().use { it.readText() })
            for (k in o.keys()) table[k] = o.getString(k)
        }.onFailure { com.hyperxr.diagnostics.Diagnostics.log("Translations: $code failed (${it.message})") }
        I18n.use(code, table)
    }
}
