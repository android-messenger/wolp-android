package org.wolpmessenger

import android.net.Uri
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebView
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebChromeClient
import org.wolpmessenger.push.PushState

/**
 * S1 §4.4. Capacitor's client asks for CAMERA / RECORD_AUDIO and then grants whatever the page
 * requested, from any origin. Here only the packaged UI (`https://<uiHost>`) gets only audio/video capture; the file
 * chooser (photo, video, voice, files from Composer) stays Capacitor's.
 */
class WolpWebChromeClient(bridge: Bridge) : BridgeWebChromeClient(bridge) {
    private val ui = Uri.parse(AppConfig.uiOrigin)

    override fun onPermissionRequest(request: PermissionRequest) {
        val resources = request.resources.toList()
        if (!request.origin.isSameOrigin(ui) || resources.isEmpty() || !resources.all { it in ALLOWED }) {
            Log.w(WolpApp.TAG, "webview permission denied: $resources for ${request.origin}")
            request.deny()
            return
        }
        // S1 §7.0: the system dialog is not "background" — but only until the request is answered
        PushState.hold(PushState.HOLD_PERMISSION, 60_000)
        super.onPermissionRequest(ReleasingRequest(request)) // runtime permissions, then grant(resources) or deny()
    }

    /** Ends the permission hold as soon as Capacitor answers the WebView's request. */
    private class ReleasingRequest(private val r: PermissionRequest) : PermissionRequest() {
        override fun getOrigin(): Uri = r.origin
        override fun getResources(): Array<String> = r.resources
        override fun grant(resources: Array<String>) {
            PushState.release(PushState.HOLD_PERMISSION)
            r.grant(resources)
        }
        override fun deny() {
            PushState.release(PushState.HOLD_PERMISSION)
            r.deny()
        }
    }

    /** S1 §7.0: the system file chooser / camera must not close the web app's WebSocket. */
    override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
        PushState.hold(PushState.HOLD_FILE_CHOOSER, 10 * 60_000L)
        val wrapped = ValueCallback<Array<Uri>> { result ->
            PushState.release(PushState.HOLD_FILE_CHOOSER)
            filePathCallback.onReceiveValue(result)
        }
        return super.onShowFileChooser(webView, wrapped, fileChooserParams).also { if (!it) PushState.release(PushState.HOLD_FILE_CHOOSER) }
    }

    private companion object {
        val ALLOWED = setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
    }
}
