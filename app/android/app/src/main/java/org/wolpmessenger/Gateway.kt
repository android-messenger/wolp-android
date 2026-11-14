package org.wolpmessenger

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.DenySource
import org.wolpmessenger.devicetrust.api.GatewayCode
import org.wolpmessenger.launch.MinVersion
import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException

/** Native calls to the gateway outside S2 (S1 §3.1, §4.2). All through S2's pinned client. */
object Gateway {
    sealed interface Ping {
        data object Ok : Ping
        data object Offline : Ping
        /** Pin mismatch: the server's key is not one of wolp.serverPins (S1 §4.3). */
        data object ServerUnverified : Ping
    }

    /**
     * `GET app/v1/device/ping` (S3 §5.8a: no Kratos session needed). Deny codes go to S2 as
     * PINNED_CLIENT (the library decides, S2 §5.3); `X-Device-Renew: 1` starts `ensureFresh()`;
     * `X-Device-Min-App-Version` is remembered by [MinVersion].
     */
    suspend fun ping(): Ping = withContext(Dispatchers.IO) {
        try {
            DeviceTrust.okHttpClient().newCall(Request.Builder().url(AppConfig.appUrl + "/v1/device/ping").build()).execute().use { r ->
                handleDeny(r)
                if (r.code == 204) MinVersion.onPing(r.header(MinVersion.HEADER))
                if (r.header("X-Device-Renew") == "1") DeviceTrust.ensureFresh()
                Ping.Ok
            }
        } catch (e: SSLPeerUnverifiedException) {
            Log.w(WolpApp.TAG, "ping: server key not pinned", e); Ping.ServerUnverified
        } catch (e: IOException) {
            Log.i(WolpApp.TAG, "ping: offline ($e)"); Ping.Offline
        }
    }

    /** Before enrollment: the enroll host answers at all (any HTTP status). Update client, no pins (S1 §3 step 5). */
    suspend fun enrollReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            DeviceTrust.updateHttpClient().newCall(Request.Builder().url(AppConfig.enrollUrl + "/v1/app/update/manifest.json").build()).execute().close()
            true
        } catch (e: IOException) {
            Log.i(WolpApp.TAG, "enroll host: offline ($e)"); false
        }
    }

    /** What `whoami` says about the Kratos session. */
    enum class Session {
        ALIVE,
        /** No cookie, or Kratos answered 401: the user is not signed in. */
        NONE,
        /** No answer or another status: the network or the server, not a sign-out. Nothing may be erased on this. */
        UNKNOWN,
    }

    /** S1 §3.1: the Kratos session by cookie and `whoami` on the enroll host. */
    suspend fun session(): Session = withContext(Dispatchers.IO) {
        val cookie = AppConfig.kratosCookie() ?: return@withContext Session.NONE
        try {
            DeviceTrust.okHttpClient().newCall(
                Request.Builder().url(AppConfig.enrollUrl + "/.ory/kratos/public/sessions/whoami").header("Cookie", cookie).header("Accept", "application/json").build()
            ).execute().use {
                when (it.code) {
                    200 -> Session.ALIVE
                    401 -> Session.NONE
                    else -> Session.UNKNOWN.also { _ -> Log.i(WolpApp.TAG, "whoami: HTTP ${it.code}") }
                }
            }
        } catch (e: IOException) {
            Log.i(WolpApp.TAG, "whoami: $e"); Session.UNKNOWN
        }
    }

    /** Kratos cookie present and `whoami` = 200. False also when Kratos could not be asked: see [session]. */
    suspend fun hasSession(): Boolean = session() == Session.ALIVE

    /**
     * Kratos browser logout on the enroll host (S1 §9 order, step 2): fetch a logout token with the
     * session cookie, then use it. False if Kratos could not be reached; the caller clears cookies anyway.
     */
    suspend fun logoutKratos(): Boolean = withContext(Dispatchers.IO) {
        val cookie = AppConfig.kratosCookie() ?: return@withContext true
        val base = AppConfig.enrollUrl + "/.ory/kratos/public/self-service/logout"
        try {
            val client = DeviceTrust.okHttpClient().newBuilder().followRedirects(false).build()
            val token = client.newCall(
                Request.Builder().url("$base/browser").header("Cookie", cookie).header("Accept", "application/json").build()
            ).execute().use { r ->
                if (r.code == 401) return@withContext true // no session any more
                if (!r.isSuccessful) return@withContext false
                JSONObject(r.body?.string() ?: "{}").optString("logout_token").ifEmpty { return@withContext false }
            }
            val url = base.toHttpUrl().newBuilder().addQueryParameter("token", token).build()
            client.newCall(Request.Builder().url(url).header("Cookie", cookie).header("Accept", "application/json").build())
                .execute().use { it.code in 200..399 }
        } catch (e: IOException) {
            Log.i(WolpApp.TAG, "kratos logout: $e"); false
        }
    }

    private suspend fun handleDeny(r: Response) {
        val h = r.header("X-Device-Deny") ?: return
        val code = GatewayCode.parse(h)
        if (code == GatewayCode.NO_SESSION || code == GatewayCode.USER_MISMATCH) return // S1 handles these elsewhere
        Log.i(WolpApp.TAG, "gateway deny $code on ${r.request.url.encodedPath}")
        DeviceTrust.onGatewayDenied(code, DenySource.PINNED_CLIENT)
    }
}
