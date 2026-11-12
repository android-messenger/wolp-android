package org.wolpmessenger.push

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.wolpmessenger.SplashActivity
import org.wolpmessenger.launch.LaunchHandoff

/**
 * Target of our own notification and call PendingIntents. Not exported: only this app (and the system
 * on behalf of its PendingIntents) can start it, so the tap it carries is ours. It keeps the tap in
 * [LaunchHandoff] and opens SplashActivity, which runs the S1 §3 order and passes the tap on to
 * MainActivity. SplashActivity itself is exported and therefore never reads a tap from its Intent:
 * another app could forge one (a call deep link with `accept=1` answers a call without asking).
 */
class TapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationTap.from(intent?.data)?.let(LaunchHandoff::offerTap)
        startActivity(Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
