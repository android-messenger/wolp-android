package org.wolpmessenger

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.util.Log
import org.wolpmessenger.push.Notifier

/**
 * S1 §5.5: native texts (screens, notifications) follow the web UI's language. The web app reports it
 * through `AppSettings.setLanguage`; it is kept as the per-app locale (LocaleManager, API 33 = our minSdk), so
 * the system applies it to every later context too — pushes while the app is closed included.
 * Until the web app has reported one, the system language decides (English unless it is Spanish).
 */
object AppLocale {
    /** Languages the web UI offers; `values/` is English, `values-es/` Spanish. */
    val SUPPORTED = setOf("en", "es")

    /** `"es"`, `"es-ES"`, `"EN_us"` → primary subtag if supported, else null. */
    fun normalize(raw: String?): String? {
        val tag = raw?.trim()?.lowercase()?.split('-', '_')?.firstOrNull() ?: return null
        return tag.takeIf { it in SUPPORTED }
    }

    /** Sets the per-app locale when it differs; channel names are re-created in the new language. */
    fun apply(context: Context, lang: String) {
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        if (lm.applicationLocales.toLanguageTags() == lang) return
        lm.applicationLocales = LocaleList.forLanguageTags(lang)
        Log.i(WolpApp.TAG, "locale: $lang")
        // a fresh context: the application context keeps its resources until the configuration change lands
        val localized = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(lang)) },
        )
        Notifier(localized).ensureChannels()
    }
}
