package org.wolpmessenger.web

import java.net.URI

/**
 * Pure argument rules of the `Files` plugin (S1 §5.4), JVM-tested. The plugin downloads with the
 * app's own pinned mTLS client and the Kratos cookie, so the URL is held to the app (API) origin:
 * the page cannot turn it into a fetcher for other hosts.
 */
object JsFiles {
    const val DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
    const val DEFAULT_NAME = "archivo"
    const val DEFAULT_MIME = "application/octet-stream"
    /** Above the web client's attachment limit (25 MB) with room; a larger body is refused. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** [url] if it is https on exactly [appOrigin] (scheme, host, port), else null. */
    fun url(url: String?, appOrigin: String): String? {
        val u = runCatching { URI(url ?: return null) }.getOrNull() ?: return null
        if (u.scheme != "https" || u.host.isNullOrEmpty() || u.rawUserInfo != null) return null
        val origin = "${u.scheme}://${u.rawAuthority}"
        return if (BridgePolicy.normalize(origin) == BridgePolicy.normalize(appOrigin)) url else null
    }

    /** A plain file name for the system save dialog: no path, no control characters, ≤ 120 chars. */
    fun fileName(name: String?): String {
        val base = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }
            .trim().trimStart('.')
        return base.take(120).ifEmpty { DEFAULT_NAME }
    }

    /** `type/subtype` (no parameters) or the generic binary type. */
    fun mimeType(mime: String?): String {
        val m = mime?.substringBefore(';')?.trim()?.lowercase() ?: return DEFAULT_MIME
        return if (Regex("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$").matches(m)) m else DEFAULT_MIME
    }

    /** Opened in a viewer right after saving (the web client opens PDFs in a tab). */
    fun viewable(mime: String): Boolean = mime == "application/pdf"
}
