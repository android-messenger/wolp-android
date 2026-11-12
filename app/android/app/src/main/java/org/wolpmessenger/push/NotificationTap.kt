package org.wolpmessenger.push

import android.net.Uri

/**
 * Data of a notification tap: the intent data of [TapActivity] and of MainActivity (neither is exported),
 * and between them — LaunchHandoff, never the Intent of the exported SplashActivity → `notificationTap`.
 */
data class NotificationTap(
    val conversationId: String?,
    val callConversationId: String?,
    val callerId: String?,
    val media: String?,
    /** S1 §7.3: «Принять» on the native call screen — the web app answers the re-sent offer without asking again. */
    val accept: Boolean = false,
) {
    fun toUri(): Uri = Uri.Builder().scheme(SCHEME).authority("tap").apply {
        conversationId?.let { appendQueryParameter("conversationId", it) }
        callConversationId?.let { appendQueryParameter("call", it) }
        callerId?.let { appendQueryParameter("caller", it) }
        media?.let { appendQueryParameter("media", it) }
        if (accept) appendQueryParameter("accept", "1")
    }.build()

    companion object {
        const val SCHEME = "wolp-push"

        fun from(uri: Uri?): NotificationTap? {
            if (uri?.scheme != SCHEME || uri.authority != "tap") return null
            fun q(k: String) = JsPush.id(uri.getQueryParameter(k))
            val call = q("call")
            val caller = q("caller")
            return NotificationTap(
                q("conversationId"), call, caller, q("media")?.takeIf { it == "audio" || it == "video" },
                accept = call != null && caller != null && uri.getQueryParameter("accept") == "1",
            )
        }
    }
}
