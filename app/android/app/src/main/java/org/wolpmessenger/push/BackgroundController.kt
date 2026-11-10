package org.wolpmessenger.push

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.wolpmessenger.WolpApp
import org.wolpmessenger.call.CallState
import org.wolpmessenger.call.WebWs

/**
 * S1 §7.0: the backend pushes only to absent users, so the web app's WebSocket must close when the
 * whole app goes to the background (ProcessLifecycleOwner ON_STOP — not an activity's onStop, which
 * the file chooser, camera and permission dialog also cause). Unless a "keep connection" hold is
 * active ([PushState]), the web app gets `appBackground` and answers `wsClosed`; without an answer
 * within 3 s (JS throttled, or a UI without the listener) the WebView is parked on about:blank, which
 * closes every connection, and the UI is loaded again on the next foreground.
 *
 * It also keeps [CallState.webWs] (S1-6, §7.3 P1-i: a native decline socket only while the web app's
 * is closed) and holds the return to the foreground while a native decline socket is open.
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
        CallState.setWebWs(WebWs.CLOSED)
        park()
    }

    fun attach() {
        CallState.setWebWs(WebWs.OPEN) // the Bridge loads the UI right away; it connects on its own
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun detach() {
        CallState.setWebWs(WebWs.CLOSED)
        PushState.appInForeground = false
        main.removeCallbacks(parkNow)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        PushState.appInForeground = true
        PushState.onForeground()
        main.removeCallbacks(parkNow)
        waiting = false
        if (!started) { started = true; return } // the catch-up event on attach is not a return from background
        // §7.3: the web app reconnects only after a native decline socket has closed (no 4409 race)
        CallState.afterNativeDecline {
            if (!PushState.appInForeground) return@afterNativeDecline // went back to the background meanwhile
            CallState.setWebWs(WebWs.OPEN)
            if (parked) {
                parked = false
                Log.i(WolpApp.TAG, "foreground: reloading the UI")
                unpark()
            } else plugin()?.emitForeground()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        PushState.appInForeground = false // a locked screen must not keep the open chat silent
        if (PushState.keepConnection()) {
            Log.i(WolpApp.TAG, "background: keeping the connection (system UI or call)")
            return
        }
        Log.i(WolpApp.TAG, "background: asking the web app to close its WebSocket")
        if (CallState.webWs.value == WebWs.OPEN) CallState.setWebWs(WebWs.CLOSING)
        plugin()?.emitBackground()
        waiting = true
        main.postDelayed(parkNow, ACK_TIMEOUT_MS)
    }

    /** `Push.wsClosed()` from the web app. */
    fun onWsClosed() {
        if (!waiting) return
        waiting = false
        main.removeCallbacks(parkNow)
        CallState.setWebWs(WebWs.CLOSED)
        Log.i(WolpApp.TAG, "background: web app closed its WebSocket")
    }

    private companion object {
        const val ACK_TIMEOUT_MS = 3_000L
    }
}
