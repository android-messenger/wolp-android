package org.wolpmessenger

import android.net.Uri
import android.util.Log
import android.webkit.PermissionRequest
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebChromeClient

/**
 * S1 §4.4. Capacitor's client asks for CAMERA / RECORD_AUDIO and then grants whatever the page
 * requested, from any origin. Here only `https://app.<host>` gets only audio/video capture; the file
 * chooser (photo, video, voice, files from Composer) stays Capacitor's.
 */
class WolpWebChromeClient(bridge: Bridge) : BridgeWebChromeClient(bridge) {
    private val app = Uri.parse(AppConfig.appUrl)

    override fun onPermissionRequest(request: PermissionRequest) {
        val resources = request.resources.toList()
        if (!request.origin.isSameOrigin(app) || resources.isEmpty() || !resources.all { it in ALLOWED }) {
            Log.w(WolpApp.TAG, "webview permission denied: $resources for ${request.origin}")
            request.deny()
            return
        }
        super.onPermissionRequest(request) // runtime permissions, then grant(resources) or deny()
    }

    private companion object {
        val ALLOWED = setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
    }
}
