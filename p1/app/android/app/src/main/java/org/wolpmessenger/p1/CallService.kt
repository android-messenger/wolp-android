package org.wolpmessenger.p1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** P1-h: minimal S1 §4.5 CallService (FGS phoneCall|microphone) to keep mic + JS alive with the screen off. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ongoing_call", "Идёт звонок", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ongoing_call")
            .setContentTitle("Идёт звонок (P1)").setSmallIcon(android.R.drawable.sym_call_outgoing).setOngoing(true).build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, 1, n, types)
            active = true
            P1Log.i("CallService: startForeground(phoneCall|microphone) ok")
        } catch (e: Exception) {
            P1Log.w("CallService: startForeground failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        P1Log.i("CallService: destroyed")
        super.onDestroy()
    }

    companion object {
        @Volatile var active = false
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, CallService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, CallService::class.java))
    }
}
