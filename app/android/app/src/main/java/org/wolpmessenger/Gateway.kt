package org.wolpmessenger

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.DenySource
import org.wolpmessenger.devicetrust.api.GatewayCode
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
     * PINNED_CLIENT (the library decides, S2 §5.3); `X-Device-Renew: 1` starts `ensureFresh()`.
     */
    suspend fun ping(): Ping = withContext(Dispatchers.IO) {
        try {
            DeviceTrust.okHttpClient().newCall(Request.Builder().url(AppConfig.appUrl + "/v1/device/ping").build()).execute().use { r ->
                handleDeny(r)
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

    /** S1 §3.1: Kratos cookie present and `whoami` = 200 on the enroll host. */
    suspend fun hasSession(): Boolean = withContext(Dispatchers.IO) {
        val cookie = AppConfig.kratosCookie() ?: return@withContext false
        try {
            DeviceTrust.okHttpClient().newCall(
                Request.Builder().url(AppConfig.enrollUrl + "/.ory/kratos/public/sessions/whoami").header("Cookie", cookie).header("Accept", "application/json").build()
            ).execute().use { it.code == 200 }
        } catch (e: IOException) {
            Log.i(WolpApp.TAG, "whoami: $e"); false
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
