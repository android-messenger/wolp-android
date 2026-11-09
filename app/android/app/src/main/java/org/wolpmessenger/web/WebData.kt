package org.wolpmessenger.web

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import org.wolpmessenger.WolpApp

/** Native state about the WebView that must survive the process (S1 §3 VaultReset, §4.1). */
object WebData {
    private const val PREFS = "wolp.webview"
    private const val CERT_VERSION = "certVersionHandled"
    private const val VAULT_RESET = "vaultResetPending"

    /** E2EE stores of messenger-ui (S1 §3 VaultReset): Signal keys, the device key and the saved plaintext. */
    val E2EE_DATABASES = listOf("e2ee-signal", "e2ee-device", "e2ee-plaintext")

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * S1 §4.1: `credentialsVersion` is compared with the last value this app handled (kept across
     * processes) and on any difference WebView forgets its client-certificate decisions.
     */
    fun onCredentialsVersion(c: Context, version: Long) {
        val p = prefs(c)
        if (p.getLong(CERT_VERSION, -1) == version) return
        WebView.clearClientCertPreferences {
            Log.i(WolpApp.TAG, "webview: client certificate preferences cleared (credentialsVersion $version)")
        }
        p.edit().putLong(CERT_VERSION, version).apply()
    }

    fun markVaultReset(c: Context) = prefs(c).edit().putBoolean(VAULT_RESET, true).commit()
    fun vaultResetPending(c: Context) = prefs(c).getBoolean(VAULT_RESET, false)
    fun vaultResetDone(c: Context) = prefs(c).edit().putBoolean(VAULT_RESET, false).apply()

    /** Name of the WebMessageListener object the reset script reports to (top frame of app.<host> only). */
    const val VAULT_RESET_LISTENER = "wolpVaultReset"

    /**
     * Runs at document start on app.<host> before any page script: deleteDatabase requests queue
     * ahead of the web app's own open() of the same database (IndexedDB orders them per origin).
     * When all are deleted it reports "done"; only then the native flag is cleared (a load that was
     * cut short must not count, and a "blocked" delete still completes once old connections close).
     */
    val vaultResetScript: String = """
        (function(){ if (window !== window.top) return;
          var names = ${E2EE_DATABASES.joinToString(",", "[", "]") { "'$it'" }}, left = names.length;
          names.forEach(function(n){ try { var q = indexedDB.deleteDatabase(n);
            q.onsuccess = function(){ if (--left === 0) $VAULT_RESET_LISTENER.postMessage('done'); };
          } catch (e) {} });
        })();
    """.trimIndent()

    /** S1 §9 logout, step 3 (native part): cookies and the WebView's client-certificate decisions. */
    fun clearSession() {
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        WebView.clearClientCertPreferences(null)
    }
}
