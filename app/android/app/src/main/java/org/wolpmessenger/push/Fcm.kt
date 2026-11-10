package org.wolpmessenger.push

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.wolpmessenger.BuildConfig
import org.wolpmessenger.WolpApp
import java.util.concurrent.TimeUnit

/**
 * Firebase initialised by hand from BuildConfig (wolp.googleServices): no google-services plugin, no
 * FirebaseInitProvider (removed in the manifest), auto-init off — a token exists only after
 * [token] is called from registration (S1 §7.1).
 */
object Fcm {
    val configured: Boolean get() = BuildConfig.FCM_APP_ID.isNotEmpty()

    @Volatile private var ready = false

    fun init(context: Context) {
        if (!configured || ready) return
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .setApplicationId(BuildConfig.FCM_APP_ID)
                        .setApiKey(BuildConfig.FCM_API_KEY)
                        .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                        .build(),
                )
            }
            ready = true
        }.onFailure { Log.w(WolpApp.TAG, "fcm: init failed", it) }
    }

    /** Blocking (call off the main thread). */
    fun token(): String? {
        if (!ready) return null
        val m = FirebaseMessaging.getInstance()
        m.isAutoInitEnabled = true // from now on FCM keeps the token fresh (onNewToken)
        return runCatching { Tasks.await(m.token, 30, TimeUnit.SECONDS) }
            .onFailure { Log.w(WolpApp.TAG, "fcm: token failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /** Blocking. Logout, unregister and Blocked (security/11 §3). */
    fun deleteToken() {
        if (!ready) return
        val m = FirebaseMessaging.getInstance()
        m.isAutoInitEnabled = false
        runCatching { Tasks.await(m.deleteToken(), 30, TimeUnit.SECONDS) }
            .onFailure { Log.w(WolpApp.TAG, "fcm: deleteToken failed: ${it.javaClass.simpleName}") }
    }
}
