package org.wolpmessenger.launch

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import org.wolpmessenger.WolpApp
import java.io.File

/**
 * S1 §3 BlockedScreen: on entering Blocked and on every start while blocked, everything the web
 * client left on the device goes — cookies, WebView storage (IndexedDB, Cache Storage, Service
 * Worker), cacheDir. Idempotent. The WebView's own directory is removed at the next process start
 * before any WebView exists ([wipePendingWebViewDir]), since deleting it under a live renderer is unsafe.
 * FCM token deletion joins with S1-7.
 */
object Wipe {
    private const val PREFS = "wolp.wipe"
    private const val PENDING = "webViewDirPending"

    fun onBlocked(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING, true).commit()
        runCatching {
            CookieManager.getInstance().apply { removeAllCookies(null); flush() }
            WebStorage.getInstance().deleteAllData()
        }.onFailure { Log.w(WolpApp.TAG, "wipe: WebView APIs failed", it) }
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        Log.i(WolpApp.TAG, "wipe: web data cleared")
    }

    /** Called from Application.onCreate before anything touches WebView. */
    fun wipePendingWebViewDir(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PENDING, false)) return
        listOf("app_webview", "app_hws_webview").forEach { File(context.dataDir, it).deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // stays pending: Blocked is final, and the next Blocked event sets it again anyway
        Log.i(WolpApp.TAG, "wipe: WebView directory removed")
    }
}
