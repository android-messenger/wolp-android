package org.wolpmessenger.push

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.wolpmessenger.WolpApp

/**
 * S1 §7.0: the backend pushes only to absent users, so the web app's WebSocket must close when the
 * whole app goes to the background (ProcessLifecycleOwner ON_STOP — not an activity's onStop, which
 * the file chooser, camera and permission dialog also cause). Unless a "keep connection" hold is
 * active ([PushState]), the web app gets `appBackground` and answers `wsClosed`; without an answer
 * within 3 s (JS throttled, or a UI without the listener) the WebView is parked on about:blank, which
 * closes every connection, and the UI is loaded again on the next foreground.
 */
class BackgroundController(
    private val plugin: () -> org.wolpmessenger.plugins.PushPlugin?,
    private val park: () -> Unit,
    private val unpark: () -> Unit,
) : DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var parked = false
    private var waiting = false
    private val parkNow = Runnable {
        waiting = false
        Log.i(WolpApp.TAG, "background: no wsClosed within ${ACK_TIMEOUT_MS} ms, parking the WebView")
        parked = true
        park()
    }

    fun attach() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    fun detach() {
        main.removeCallbacks(parkNow)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        PushState.onForeground()
        main.removeCallbacks(parkNow)
        waiting = false
        if (!started) { started = true; return } // the catch-up event on attach is not a return from background
        if (parked) {
            parked = false
            Log.i(WolpApp.TAG, "foreground: reloading the UI")
            unpark()
        } else plugin()?.emitForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (PushState.keepConnection()) {
            Log.i(WolpApp.TAG, "background: keeping the connection (system UI or call)")
            return
        }
        Log.i(WolpApp.TAG, "background: asking the web app to close its WebSocket")
        plugin()?.emitBackground()
        waiting = true
        main.postDelayed(parkNow, ACK_TIMEOUT_MS)
    }

    /** `Push.wsClosed()` from the web app. */
    fun onWsClosed() {
        if (!waiting) return
        waiting = false
        main.removeCallbacks(parkNow)
        Log.i(WolpApp.TAG, "background: web app closed its WebSocket")
    }

    private companion object {
        const val ACK_TIMEOUT_MS = 3_000L
    }
}
