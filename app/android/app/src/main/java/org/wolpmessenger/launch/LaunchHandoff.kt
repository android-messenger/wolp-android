package org.wolpmessenger.launch

import org.wolpmessenger.push.NotificationTap

/**
 * What the app's own components hand to each other around the S1 §3 order, kept in the process and
 * never in an Intent. SplashActivity is exported (launcher), so anything in its Intent — data, extras —
 * may come from another app and is ignored; only values put here by our own code count:
 *
 *  - a notification tap (TapActivity, not exported → SplashActivity → MainActivity → `notificationTap`);
 *  - the reason MainActivity handed over with (the order alone would not show that screen);
 *  - the clearance for MainActivity: set only when the order ended in «to the messenger», consumed by
 *    the MainActivity it starts. Without it (the system restored the activity after the process died,
 *    or anything else started it) MainActivity loads nothing and goes back to the order (ADR-0001 п. 3).
 *
 * Pure (no Android types of its own), JVM-tested.
 */
object LaunchHandoff {
    /** A tap older than this is dropped: the order may stop at login or onboarding for a long time. */
    const val TAP_TTL_MS = 120_000L

    private var tap: NotificationTap? = null
    private var tapAt = 0L
    private var reason: String? = null
    private var cleared = false

    private fun now(): Long = System.nanoTime() / 1_000_000

    @Synchronized
    fun offerTap(t: NotificationTap, now: Long = now()) {
        tap = t
        tapAt = now
    }

    /** The pending tap, once; null when there is none or it is older than [TAP_TTL_MS]. */
    @Synchronized
    fun takeTap(now: Long = now()): NotificationTap? {
        val t = tap ?: return null
        tap = null
        return t.takeIf { now - tapAt <= TAP_TTL_MS }
    }

    @Synchronized
    fun offerReason(r: String) {
        reason = r
    }

    @Synchronized
    fun takeReason(): String? = reason.also { reason = null }

    /** The S1 §3 order ended in «to the messenger» (SplashActivity), or MainActivity is being re-created in place. */
    @Synchronized
    fun clearMessenger() {
        cleared = true
    }

    /** Consumed by MainActivity: true only once per [clearMessenger]. */
    @Synchronized
    fun takeClearance(): Boolean = cleared.also { cleared = false }

    /** Tests only. */
    @Synchronized
    internal fun reset() {
        tap = null
        reason = null
        cleared = false
    }
}
