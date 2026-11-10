package org.wolpmessenger.push

import android.os.SystemClock

/**
 * Process-wide push state: the conversation open on screen (§7.1, no notification for it) and the
 * "keep the connection" holds of §7.0 (system file chooser, camera, permission dialog, calls), during
 * which going to the background does not close the web app's WebSocket.
 */
object PushState {
    @Volatile var foregroundConversation: String? = null

    private val holds = HashMap<String, Long>() // name → expiry (elapsedRealtime), Long.MAX_VALUE = until released

    @Synchronized
    fun hold(name: String, maxMs: Long = Long.MAX_VALUE) {
        holds[name] = if (maxMs == Long.MAX_VALUE) Long.MAX_VALUE else SystemClock.elapsedRealtime() + maxMs
    }

    @Synchronized
    fun release(name: String) {
        holds.remove(name)
    }

    @Synchronized
    fun keepConnection(): Boolean {
        val now = SystemClock.elapsedRealtime()
        holds.entries.removeAll { it.value <= now }
        return holds.isNotEmpty()
    }

    /** Short holds end when the app is back in the foreground. */
    @Synchronized
    fun onForeground() {
        holds.entries.removeAll { it.value != Long.MAX_VALUE }
    }

    const val HOLD_FILE_CHOOSER = "file-chooser"
    const val HOLD_SAVE_DOCUMENT = "save-document"
    const val HOLD_PERMISSION = "permission"
    const val HOLD_CALL = "call"
}
