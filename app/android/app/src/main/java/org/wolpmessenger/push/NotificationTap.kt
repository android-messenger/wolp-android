package org.wolpmessenger.push

import android.net.Uri

/**
 * Data of a notification tap, carried as the intent data of SplashActivity → MainActivity
 * (MainActivity is not exported and starts only after the S1 §3 order) → `notificationTap`.
 */
data class NotificationTap(val conversationId: String?, val callConversationId: String?, val callerId: String?, val media: String?) {
    fun toUri(): Uri = Uri.Builder().scheme(SCHEME).authority("tap").apply {
        conversationId?.let { appendQueryParameter("conversationId", it) }
        callConversationId?.let { appendQueryParameter("call", it) }
        callerId?.let { appendQueryParameter("caller", it) }
        media?.let { appendQueryParameter("media", it) }
    }.build()

    companion object {
        const val SCHEME = "wolp-push"

        fun from(uri: Uri?): NotificationTap? {
            if (uri?.scheme != SCHEME || uri.authority != "tap") return null
            fun q(k: String) = JsPush.id(uri.getQueryParameter(k))
            return NotificationTap(q("conversationId"), q("call"), q("caller"), q("media")?.takeIf { it == "audio" || it == "video" })
        }
    }
}
