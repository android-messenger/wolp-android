package org.wolpmessenger.push

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.wolpmessenger.AppConfig
import org.wolpmessenger.BuildConfig
import org.wolpmessenger.WolpApp
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.TrustState
import java.io.IOException
import java.util.Base64

/**
 * Registration with `firebase-push` (S1 §7.1): FCM token + payload key → `POST
 * https://app.<host>/firebase-push/tokens` over S2's pinned mTLS client with the Kratos cookie. The
 * gateway takes the device id from the client certificate, never from the body. Without a session or
 * a certificate registration waits for the next `Push.register()` (the web app calls it on start).
 */
class PushRegistrar(private val context: Context) {
    enum class Result { OK, DISABLED, NOT_READY, NO_TOKEN, FAILED }

    private val keys = PushKeys(context)

    suspend fun register(token: String? = null): Result = mutex.withLock {
        if (!Fcm.configured) return Result.DISABLED
        val s = DeviceTrust.state.value
        if (s !is TrustState.Active && s !is TrustState.RenewalDue) return Result.NOT_READY
        val cookie = AppConfig.kratosCookie() ?: return Result.NOT_READY
        val t = token ?: withContext(Dispatchers.IO) { Fcm.token() } ?: return Result.NO_TOKEN
        val key = keys.keyFor(t)
        val body = JSONObject()
            .put("token", t)
            .put("payloadKey", Base64.getEncoder().encodeToString(key))
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .toString()
        key.fill(0)
        val req = Request.Builder()
            .url(AppConfig.appUrl.trimEnd('/') + PATH)
            .header("Cookie", cookie)
            .post(body.toRequestBody(JSON))
            .build()
        return withContext(Dispatchers.IO) {
            try {
                DeviceTrust.okHttpClient().newCall(req).execute().use { r ->
                    if (r.isSuccessful) Result.OK.also { Log.i(WolpApp.TAG, "push: registered") }
                    else Result.FAILED.also { Log.w(WolpApp.TAG, "push: register HTTP ${r.code} ${r.header("X-Device-Deny").orEmpty()}") }
                }
            } catch (e: IOException) {
                Log.w(WolpApp.TAG, "push: register failed: ${e.javaClass.simpleName}")
                Result.FAILED
            }
        }
    }

    /** Before logging out of Kratos (S1 §9): the server forgets this device's token, FCM drops it. */
    suspend fun unregister() = mutex.withLock {
        val cookie = AppConfig.kratosCookie()
        withContext(Dispatchers.IO) {
            if (cookie != null) {
                val req = Request.Builder().url(AppConfig.appUrl.trimEnd('/') + PATH).header("Cookie", cookie).delete().build()
                try {
                    DeviceTrust.okHttpClient().newCall(req).execute().use { r -> Log.i(WolpApp.TAG, "push: unregister HTTP ${r.code}") }
                } catch (e: IOException) {
                    Log.w(WolpApp.TAG, "push: unregister failed: ${e.javaClass.simpleName}")
                }
            }
            Fcm.deleteToken()
        }
        keys.clear()
    }

    private companion object {
        /** One registration at a time per process (plugin, onNewToken). */
        val mutex = Mutex()
        const val PATH = "/firebase-push/tokens"
        val JSON = "application/json".toMediaType()
    }
}
