package org.wolpmessenger.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.wolpmessenger.MainActivity
import org.wolpmessenger.R
import org.wolpmessenger.WolpApp
import org.wolpmessenger.push.Notifier
import org.wolpmessenger.push.PushState

/**
 * S1 §4.5 active call: FGS `phoneCall|microphone` (+ `camera` for video) started by the web app via
 * `Call.started()` while the app is in the foreground, so the WebView keeps the microphone and camera
 * when the app is minimised or the screen goes off. Holds the §7.0 "keep connection" hold (the
 * WebSocket stays open in the background), the audio mode/route ([CallAudio]) and the proximity lock.
 * Ongoing notification «Идёт звонок» with «Завершить» → `hangupFromNotification`.
 * Stops on `Call.ended()`, or 10 s after the WebView went away without it.
 */
class CallService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var audio: CallAudio? = null
    private var proximity: PowerManager.WakeLock? = null
    private var video = false
    private var peerName: String? = null
    private val selfStop = Runnable {
        Log.w(WolpApp.TAG, "call: no Call.ended() within ${ORPHAN_MS / 1000} s, stopping")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        PushState.hold(PushState.HOLD_CALL)
        audio = CallAudio(this) { route ->
            updateProximity(route)
            CallState.emit(CallState.EVENT_ROUTE, mapOf("route" to route.js))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                video = intent.getStringExtra(EXTRA_MEDIA) == "video"
                peerName = intent.getStringExtra(EXTRA_NAME)
                foreground()
                audio?.start(video)
                main.removeCallbacks(selfStop)
            }
            ACTION_MEDIA -> {
                video = intent.getStringExtra(EXTRA_MEDIA) == "video"
                foreground()
                audio?.mediaChanged(video)
            }
            ACTION_HANGUP -> {
                Log.i(WolpApp.TAG, "call: hang up from the notification")
                if (!CallState.emit(CallState.EVENT_HANGUP)) stopSelf() // nobody to hang up the WebRTC call
                else main.postDelayed(selfStop, ORPHAN_MS)
            }
            ACTION_WEBVIEW_GONE -> main.postDelayed(selfStop, ORPHAN_MS)
            else -> if (audio?.started != true) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(selfStop)
        audio?.stop()
        audio = null
        updateProximity(null)
        PushState.release(PushState.HOLD_CALL)
        if (running === this) running = null
        super.onDestroy()
    }

    /** startForeground with the types the granted permissions allow (a missing one would throw). */
    private fun foreground() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (video && granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        try {
            startForeground(ID, notification(), types)
        } catch (e: SecurityException) {
            // camera/microphone from the background (video switched on while minimised): keep the call type only
            Log.w(WolpApp.TAG, "call: FGS types $types refused (${e.message}), phoneCall only")
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun updateProximity(route: AudioRoute?) {
        val want = route != null && CallRules.proximity(video, route)
        if (want && proximity == null) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "wolp:call-proximity").apply { acquire(MAX_CALL_MS) }
            }
        } else if (!want) {
            proximity?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
            proximity = null
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, RC_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangup = PendingIntent.getService(
            this, RC_HANGUP, Intent(this, CallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val person = Person.Builder().setName(peerName ?: ONGOING).build()
        val publicVersion = Notification.Builder(this, Notifier.CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ONGOING)
            .build()
        return Notification.Builder(this, Notifier.CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ONGOING)
            .setStyle(Notification.CallStyle.forOngoingCall(person, hangup))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(true)
            .build()
    }

    companion object {
        private const val ID = 4
        private const val RC_OPEN = 41
        private const val RC_HANGUP = 42
        private const val ORPHAN_MS = 10_000L
        private const val MAX_CALL_MS = 6 * 60 * 60_000L
        private const val ONGOING = "Идёт звонок"
        private const val ACTION_START = "org.wolpmessenger.call.START"
        private const val ACTION_MEDIA = "org.wolpmessenger.call.MEDIA"
        private const val ACTION_HANGUP = "org.wolpmessenger.call.HANGUP"
        private const val ACTION_WEBVIEW_GONE = "org.wolpmessenger.call.WEBVIEW_GONE"
        private const val EXTRA_MEDIA = "media"
        private const val EXTRA_NAME = "peerName"

        /** The running service (main thread only); the plugin reads routes through it. */
        @Volatile var running: CallService? = null
            private set

        val audioOrNull: CallAudio? get() = running?.audio

        /** `Call.started()` — the app is in the foreground, so a `microphone` FGS may start. */
        fun start(context: Context, media: String, peerName: String?) {
            context.startForegroundService(
                Intent(context, CallService::class.java).setAction(ACTION_START).putExtra(EXTRA_MEDIA, media).putExtra(EXTRA_NAME, peerName),
            )
        }

        fun mediaChanged(context: Context, media: String) {
            if (running == null) return
            context.startService(Intent(context, CallService::class.java).setAction(ACTION_MEDIA).putExtra(EXTRA_MEDIA, media))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        /** MainActivity destroyed: without `Call.ended()` within 10 s the service stops itself. */
        fun webViewGone(context: Context) {
            if (running == null) return
            context.startService(Intent(context, CallService::class.java).setAction(ACTION_WEBVIEW_GONE))
        }
    }
}
