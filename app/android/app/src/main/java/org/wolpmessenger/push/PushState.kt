package org.wolpmessenger.push

import android.os.SystemClock

/**
 * Process-wide push state: the conversation open on screen (§7.1, no notification for it) and the
 * "keep the connection" holds of §7.0 (system file chooser, camera, permission dialog, calls), during
 * which going to the background does not close the web app's WebSocket.
 */
object PushState {
    @Volatile var foregroundConversation: String? = null

    /** The whole app is in the foreground (ProcessLifecycleOwner, set by BackgroundController). */
    @Volatile var appInForeground: Boolean = false

    /** The conversation whose notifications are suppressed: only while the app is actually on screen. */
    fun suppressedConversation(): String? = if (appInForeground) foregroundConversation else null

    private val holds = HashMap<String, Long>() // name → expiry (elapsedRealtime), Long.MAX_VALUE = until released

    /**
     * Called (on the releasing thread, outside the lock) after a release left no hold. The app may be in the
     * background with the WebSocket kept open for that hold: BackgroundController closes it then.
     */
    @Volatile var onAllReleased: (() -> Unit)? = null

    @Synchronized
    fun hold(name: String, maxMs: Long = Long.MAX_VALUE, now: Long = SystemClock.elapsedRealtime()) {
        holds[name] = if (maxMs == Long.MAX_VALUE) Long.MAX_VALUE else now + maxMs
    }

    fun release(name: String, now: Long = SystemClock.elapsedRealtime()) {
        val none = synchronized(this) {
            holds.remove(name) ?: return
            prune(now)
            holds.isEmpty()
        }
        if (none) onAllReleased?.invoke()
    }

    @Synchronized
    fun keepConnection(now: Long = SystemClock.elapsedRealtime()): Boolean {
        prune(now)
        return holds.isNotEmpty()
    }

    /** Time until the earliest time-limited hold runs out, or null if there is none (only open-ended holds or no hold). */
    @Synchronized
    fun msUntilNextExpiry(now: Long = SystemClock.elapsedRealtime()): Long? {
        prune(now)
        return holds.values.filter { it != Long.MAX_VALUE }.minOrNull()?.let { (it - now).coerceAtLeast(0) }
    }

    /** Short holds end when the app is back in the foreground. */
    @Synchronized
    fun onForeground() {
        holds.entries.removeAll { it.value != Long.MAX_VALUE }
    }

    /** Tests only. */
    @Synchronized
    internal fun clear() {
        holds.clear()
        onAllReleased = null
    }

    private fun prune(now: Long) {
        holds.entries.removeAll { it.value <= now }
    }

    const val HOLD_FILE_CHOOSER = "file-chooser"
    const val HOLD_SAVE_DOCUMENT = "save-document"
    const val HOLD_PERMISSION = "permission"
    const val HOLD_CALL = "call"
}
