package org.wolpmessenger.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.wolpmessenger.R

/**
 * Local notifications for push signals (S1 §7.1). Texts never come from the push: names from the
 * local cache, otherwise a generic text (res/values(-es)/strings.xml, language of the web UI — S1 §5.5). A tap opens [TapActivity] (not exported), which
 * hands it to SplashActivity (S1 §3 order first) → MainActivity → `notificationTap`.
 *
 * A push call rings through RingService (S1-6, §7.3) on the silent `ringing` channel (the service
 * plays the ringtone itself); the insistent `calls` notification is the fallback when the FGS cannot start.
 */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val generic get() = context.getString(R.string.notif_new_message)
    private val incomingCall get() = context.getString(R.string.call_incoming)
    private val missedCall get() = context.getString(R.string.call_missed)

    fun ensureChannels() {
        val calls = NotificationChannel(CH_CALLS, context.getString(R.string.notif_channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
            )
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MESSAGES, context.getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                calls,
                NotificationChannel(CH_RINGING, context.getString(R.string.notif_channel_ringing), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null) // RingService loops the ringtone and vibrates itself
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(CH_ONGOING, context.getString(R.string.notif_channel_ongoing), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_SYSTEM, context.getString(R.string.notif_channel_system), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun message(conversationId: String?, senderName: String?) {
        val title = senderName ?: generic
        val publicVersion = Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(generic)
            .build()
        val n = Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (senderName != null) generic else null)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setGroup(conversationId ?: GROUP_UNKNOWN)
            .setContentIntent(tap(NotificationTap(conversationId, null, null, null), requestCode(conversationId)))
            .build()
        nm.notify(tagFor(conversationId), ID_MESSAGE, n)
    }

    fun call(conversationId: String, callerId: String, media: String?, callerName: String?) {
        val publicVersion = Notification.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(incomingCall)
            .setContentText(callerName) // D16: the name on the lock screen too
            .build()
        val tapIntent = tap(NotificationTap(null, conversationId, callerId, media), requestCode("call:$conversationId"))
        val n = Notification.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(incomingCall)
            .setContentText(callerName)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setTimeoutAfter(PushRules.CALL_RING_TIMEOUT.toMillis())
            .setContentIntent(tapIntent)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify("call:$conversationId", ID_CALL, n)
    }

    fun cancelCall(conversationId: String) {
        nm.cancel("call:$conversationId", ID_CALL)
    }

    /** §7.3: the ring ended without an answer (timeout or the caller hung up). Tap → the conversation. */
    fun missedCall(conversationId: String, callerName: String?) {
        val publicVersion = Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(missedCall)
            .build()
        val n = Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(missedCall)
            .setContentText(callerName)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setContentIntent(tap(NotificationTap(conversationId, null, null, null), requestCode("missed:$conversationId")))
            .build()
        nm.notify("missed:$conversationId", ID_MISSED, n)
    }

    /** API 34+: the user may have revoked full-screen intents; API 33 has no such check (§7.3). */
    fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent()

    /** §7.3: without full-screen intents a call is only a heads-up — say where to turn them on. */
    fun fullScreenHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val settings = PendingIntent.getActivity(
            context, ID_FSI_HINT,
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CH_SYSTEM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.fsi_hint_title))
            .setContentText(context.getString(R.string.fsi_hint_text))
            .setAutoCancel(true)
            .setContentIntent(settings)
            .build()
        nm.notify(ID_FSI_HINT, n)
    }

    /** Opening a conversation clears its notifications. */
    fun cancelConversation(conversationId: String) {
        nm.cancel(tagFor(conversationId), ID_MESSAGE)
    }

    fun cancelAll() = nm.cancelAll()

    private fun tap(t: NotificationTap, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, TapActivity::class.java).setData(t.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun tagFor(conversationId: String?) = "msg:" + (conversationId ?: GROUP_UNKNOWN)
    private fun requestCode(key: String?) = (key ?: GROUP_UNKNOWN).hashCode()

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_CALLS = "calls"
        const val CH_RINGING = "ringing"
        const val CH_ONGOING = "ongoing_call"
        const val CH_SYSTEM = "system"
        private const val GROUP_UNKNOWN = "unknown"
        private const val ID_MESSAGE = 1
        private const val ID_CALL = 2
        private const val ID_MISSED = 5
        private const val ID_FSI_HINT = 6
    }
}
