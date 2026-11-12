package org.wolpmessenger.call

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.launch
import org.wolpmessenger.R
import org.wolpmessenger.WolpApp
import org.wolpmessenger.push.NotificationTap
import org.wolpmessenger.push.Notifier
import org.wolpmessenger.push.TapActivity

/**
 * S1 §7.3 incoming push call: a short FGS (`phoneCall`, allowed from a high-priority FCM) that loops
 * the ringtone and vibrates until Accept, Decline, `call_cancel` or the timeout. Its notification is a
 * CallStyle one with a full-screen intent to [IncomingCallActivity]; the caller's name is shown on the
 * lock screen too (D16, owner 2026-11-11). One call at a time: a second push call while ringing is ignored (the
 * caller hears the end by its own timeout).
 */
class RingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var call: IncomingCall? = null
    private var declining = false
    private val timeout = Runnable { finish(Outcome.MISSED) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> ring(intent)
            ACTION_ACCEPT -> finish(Outcome.ACCEPTED)
            ACTION_DECLINE -> finish(Outcome.DECLINED)
            ACTION_CANCEL -> if (intent.getStringExtra(EXTRA_CONVERSATION) == call?.conversationId) finish(Outcome.MISSED)
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun ring(intent: Intent) {
        val incoming = IncomingCall(
            conversationId = intent.getStringExtra(EXTRA_CONVERSATION) ?: return stopIfIdle(),
            callerId = intent.getStringExtra(EXTRA_CALLER) ?: return stopIfIdle(),
            media = intent.getStringExtra(EXTRA_MEDIA),
            callerName = intent.getStringExtra(EXTRA_NAME),
        )
        // startForegroundService obliges startForeground even for a call we ignore
        startForeground(ID, notification(call ?: incoming), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        if (call != null) {
            Log.i(WolpApp.TAG, "ring: busy with another push call, ignoring")
            return
        }
        call = incoming
        CallState.ringing.value = incoming
        startRinging()
        main.postDelayed(timeout, intent.getLongExtra(EXTRA_RING_MS, DEFAULT_RING_MS))
        if (!Notifier(this).canUseFullScreenIntent()) Notifier(this).fullScreenHint()
    }

    private fun stopIfIdle() { if (call == null && !declining) stopSelf() }

    private fun finish(outcome: Outcome) {
        val c = call ?: return run { if (!declining) stopSelf() }
        call = null
        main.removeCallbacks(timeout)
        stopRinging()
        CallState.ringing.value = null
        Log.i(WolpApp.TAG, "ring: ${outcome.name.lowercase()}")
        when (outcome) {
            Outcome.ACCEPTED -> Unit // IncomingCallActivity opens the messenger with the deep link
            Outcome.DECLINED -> {
                // Stay a foreground service until the ≤ 5 s decline socket is done: a background
                // process loses the network under battery saver / Doze, and could be killed meanwhile.
                startForeground(ID, declinedNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                declining = true
                (application as WolpApp).scope.launch {
                    NativeDecline.decline(c)
                    declining = false
                    if (call == null) { // a new push call may have started ringing meanwhile
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return
            }
            Outcome.MISSED -> Notifier(this).missedCall(c.conversationId, c.callerName)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun declinedNotification(): Notification = Notification.Builder(this, Notifier.CH_ONGOING)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.call_declined))
        .setCategory(Notification.CATEGORY_CALL)
        .build()

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        stopRinging()
        if (call != null) CallState.ringing.value = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ sound

    private fun startRinging() {
        val am = getSystemService(AudioManager::class.java)
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
            )
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
    }

    // ------------------------------------------------------------ notification

    private fun notification(c: IncomingCall): Notification {
        val fullScreen = PendingIntent.getActivity(
            this, RC_FULL_SCREEN,
            Intent(this, IncomingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Accept goes through the activity (it dismisses the keyguard first); Decline straight to the service.
        val accept = PendingIntent.getActivity(
            this, RC_ACCEPT,
            Intent(this, IncomingCallActivity::class.java).setAction(IncomingCallActivity.ACTION_ACCEPT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getService(
            this, RC_DECLINE, Intent(this, RingService::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val caller = Person.Builder().setName(c.callerName ?: getString(R.string.call_incoming)).setImportant(true).build()
        val publicVersion = Notification.Builder(this, Notifier.CH_RINGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(c.callerName ?: getString(R.string.call_incoming)) // D16: the name on the lock screen too
            .setContentText(if (c.callerName != null) getString(R.string.call_incoming) else null)
            .build()
        return Notification.Builder(this, Notifier.CH_RINGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.call_incoming))
            .setStyle(Notification.CallStyle.forIncomingCall(caller, decline, accept))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .build()
    }

    enum class Outcome { ACCEPTED, DECLINED, MISSED }

    companion object {
        private const val ID = 3
        private const val RC_FULL_SCREEN = 31
        private const val RC_ACCEPT = 32
        private const val RC_DECLINE = 33
        private const val DEFAULT_RING_MS = 45_000L
        private const val ACTION_RING = "org.wolpmessenger.call.RING"
        const val ACTION_ACCEPT = "org.wolpmessenger.call.ACCEPT"
        const val ACTION_DECLINE = "org.wolpmessenger.call.DECLINE"
        private const val ACTION_CANCEL = "org.wolpmessenger.call.CANCEL"
        private const val EXTRA_CONVERSATION = "conversationId"
        private const val EXTRA_CALLER = "callerId"
        private const val EXTRA_MEDIA = "media"
        private const val EXTRA_NAME = "callerName"
        private const val EXTRA_RING_MS = "ringMs"

        /** From the FCM service. False — the FGS could not start: the caller falls back to the insistent notification. */
        fun start(context: Context, call: IncomingCall, ringMs: Long): Boolean = try {
            context.startForegroundService(
                Intent(context, RingService::class.java).setAction(ACTION_RING)
                    .putExtra(EXTRA_CONVERSATION, call.conversationId)
                    .putExtra(EXTRA_CALLER, call.callerId)
                    .putExtra(EXTRA_MEDIA, call.media)
                    .putExtra(EXTRA_NAME, call.callerName)
                    .putExtra(EXTRA_RING_MS, ringMs),
            )
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(WolpApp.TAG, "ring: FGS not allowed (${e.message}), falling back to a notification")
            false
        } catch (e: SecurityException) {
            Log.w(WolpApp.TAG, "ring: FGS refused (${e.javaClass.simpleName}), falling back to a notification")
            false
        }

        /** `call_cancel` push (D19). Only reaches a running service; nothing to do otherwise. */
        fun cancel(context: Context, conversationId: String) {
            if (CallState.ringing.value?.conversationId != conversationId) return
            context.startService(Intent(context, RingService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_CONVERSATION, conversationId))
        }

        fun accept(context: Context) = send(context, ACTION_ACCEPT)
        fun decline(context: Context) = send(context, ACTION_DECLINE)

        private fun send(context: Context, action: String) {
            if (CallState.ringing.value == null) return
            context.startService(Intent(context, RingService::class.java).setAction(action))
        }

        /**
         * The deep link MainActivity hands to the web app: `?call=…&caller=…&media=…&accept=1`. Through
         * TapActivity (not exported): `accept` must never be something another app can send.
         */
        fun acceptIntent(context: Context, c: IncomingCall): Intent =
            Intent(context, TapActivity::class.java)
                .setData(NotificationTap(null, c.conversationId, c.callerId, c.media, accept = true).toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
