package org.wolpmessenger

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.TrustEvent
import org.wolpmessenger.launch.Wipe
import org.wolpmessenger.web.WebData

class WolpApp : Application() {
    /** Process-wide scope (the process also starts for FCM without any activity). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Wipe.wipePendingWebViewDir(this) // before anything creates a WebView
        DeviceTrust.init(this, AppConfig.deviceTrust())
        // The single subscriber of the process (API.md §5): events emitted by the start-up
        // reconciliation before this line are buffered, not lost.
        scope.launch { DeviceTrust.events.collect(::onTrustEvent) }
        scope.launch { DeviceTrust.state.collect { Log.i(TAG, "trust state $it") } } // no secrets in TrustState
    }

    private fun onTrustEvent(e: TrustEvent) {
        Log.i(TAG, "trust event $e")
        when (e) {
            // S1 §3: wipe on entering Blocked and on every start while blocked (the screen follows the state)
            is TrustEvent.Blocked -> Wipe.onBlocked(this)
            // S1 §3 VaultReset: MainActivity clears the web app's E2EE stores before app.<host> runs any
            // script (the event comes from start-up reconciliation, before the messenger opens).
            // Names cache and Push.register() join with S1-7.
            TrustEvent.VaultReset -> WebData.markVaultReset(this)
        }
    }

    companion object {
        const val TAG = "Wolp"
    }
}
