package org.wolpmessenger.plugins

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.wolpmessenger.AppConfig
import org.wolpmessenger.WolpApp
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.push.PushState
import org.wolpmessenger.web.BridgePolicy
import org.wolpmessenger.web.CallOrigins
import org.wolpmessenger.web.JsDeviceTrust
import org.wolpmessenger.web.JsFiles
import java.io.IOException

/**
 * `Files` plugin (S1 §5.4): saving an attachment the web client would download with
 * `window.open` / `<a download>` — which in the app would either be refused (a document of the API
 * host, security/09) or hand a presigned URL to another app. The user picks the place in the system
 * dialog (SAF, no storage permission); the native side streams the file with S2's pinned mTLS client
 * and the Kratos cookie straight into that document. Same origin rule as `DeviceTrust` (S1 §5).
 */
@CapacitorPlugin(name = "Files")
class FilesPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun handleOnDestroy() {
        scope.cancel()
    }

    /** `saveDownload({url, fileName?, mimeType?})` → `{saved: boolean, opened?: boolean}`; cancel → `saved: false`. */
    @PluginMethod
    fun saveDownload(call: PluginCall) {
        if (!CallOrigins.allowed(call.callbackId, AppConfig.uiOrigin)) {
            Log.w(WolpApp.TAG, "Files.saveDownload: refused (not the app's main frame)")
            return call.reject("plugin call refused", BridgePolicy.PERMISSION_DENIED)
        }
        JsFiles.url(call.getString("url"), AppConfig.appOrigin)
            ?: return call.reject("url: https on the app host expected", JsDeviceTrust.INVALID_ARGUMENT)
        val mime = JsFiles.mimeType(call.getString("mimeType"))
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, JsFiles.fileName(call.getString("fileName")))
        PushState.hold(PushState.HOLD_SAVE_DOCUMENT, 10 * 60_000L) // S1 §7.0
        startActivityForResult(call, intent, "onDocumentChosen")
    }

    @ActivityCallback
    private fun onDocumentChosen(call: PluginCall?, result: ActivityResult) {
        PushState.release(PushState.HOLD_SAVE_DOCUMENT)
        call ?: return
        val target = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || target == null) {
            return call.resolve(JSObject().put("saved", false))
        }
        // validated in saveDownload; read again from the same call
        val url = JsFiles.url(call.getString("url"), AppConfig.appOrigin)
            ?: return call.reject("url: https on the app host expected", JsDeviceTrust.INVALID_ARGUMENT)
        val mime = JsFiles.mimeType(call.getString("mimeType"))
        scope.launch {
            val error = withContext(Dispatchers.IO) { download(url, target) }
            if (error != null) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                Log.w(WolpApp.TAG, "Files.saveDownload failed: $error")
                return@launch call.reject("download failed: $error", JsFiles.DOWNLOAD_FAILED)
            }
            val opened = JsFiles.viewable(mime) && open(target, mime)
            call.resolve(JSObject().put("saved", true).put("opened", opened))
        }
    }

    /** null on success, else a short reason (no URL: it carries a presigned signature). */
    private fun download(url: String, target: Uri): String? = try {
        val req = Request.Builder().url(url).apply { AppConfig.kratosCookie()?.let { header("Cookie", it) } }.build()
        DeviceTrust.okHttpClient().newCall(req).execute().use { r ->
            if (!r.isSuccessful) return "HTTP ${r.code}${r.header("X-Device-Deny")?.let { " $it" } ?: ""}"
            val body = r.body ?: return "empty body"
            if (body.contentLength() > JsFiles.MAX_BYTES) return "too large"
            val out = context.contentResolver.openOutputStream(target, "w") ?: return "cannot write the document"
            out.use { o ->
                body.byteStream().use { i ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > JsFiles.MAX_BYTES) return "too large"
                        o.write(buf, 0, n)
                    }
                }
            }
            null
        }
    } catch (e: IOException) {
        e.javaClass.simpleName
    } catch (e: SecurityException) {
        "no access to the document"
    }

    private fun open(target: Uri, mime: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(target, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
