package org.wolpmessenger

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wolpmessenger.push.EventDedup
import org.wolpmessenger.push.Fcm
import org.wolpmessenger.push.NameCache
import org.wolpmessenger.push.Notifier
import org.wolpmessenger.push.PushKeys
import org.wolpmessenger.push.PushRegistrar
import org.wolpmessenger.push.SignedOut
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.TrustEvent
import org.wolpmessenger.launch.MinVersion
import org.wolpmessenger.launch.Wipe
import org.wolpmessenger.web.WebData

class WolpApp : Application() {
    /** Process-wide scope (the process also starts for FCM without any activity). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Wipe.wipePendingWebViewDir(this) // before anything creates a WebView
        MinVersion.init(this)
        DeviceTrust.init(this, AppConfig.deviceTrust())
        Fcm.init(this)                    // S1 §7.1: messaging only, no auto-init
        Notifier(this).ensureChannels()
        // The single subscriber of the process (API.md §5): events emitted by the start-up
        // reconciliation before this line are buffered, not lost.
        scope.launch { DeviceTrust.events.collect(::onTrustEvent) }
        scope.launch { DeviceTrust.state.collect { Log.i(TAG, "trust state $it") } } // no secrets in TrustState
        cleanUpAfterSignOut()
        watchNetwork()
        // every return of the app to the screen, whichever activity shows (after a sign-out it is the login screen)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = cleanUpAfterSignOut()
        })
    }

    private fun onTrustEvent(e: TrustEvent) {
        Log.i(TAG, "trust event $e")
        when (e) {
            // S1 §3: wipe on entering Blocked and on every start while blocked (the screen follows the state)
            is TrustEvent.Blocked -> {
                Wipe.onBlocked(this)
                scope.launch { wipePush() }
            }
            // S1 §3 VaultReset: MainActivity clears the web app's E2EE stores before app.<host> runs any
            // script (the event comes from start-up reconciliation, before the messenger opens).
            // The push key and the names cache were Vault-wrapped too: drop them; the web app's next
            // Push.register() makes a new key for the token.
            TrustEvent.VaultReset -> {
                WebData.markVaultReset(this)
                PushKeys(this).clear()
                NameCache(this).clear()
            }
            // spec 001 story 2: the library moved the E2EE half under the screen-lock key and replaced the
            // background key, so the push key and the names cache are unreadable; E2EE data is intact —
            // do NOT markVaultReset (it wipes the web app's E2EE stores). Register again at once: the event
            // may come on a return to the foreground, after the web app has already registered this start.
            TrustEvent.BackgroundKeysReset -> {
                PushKeys(this).clear()
                NameCache(this).clear()
                scope.launch { Log.i(TAG, "push: re-register after BackgroundKeysReset → ${PushRegistrar(this@WolpApp).register()}") }
            }
        }
    }

    /**
     * The Kratos session ended outside the web client's logout (S1 §9): the same push clean-up as on Blocked,
     * and from now on no push is shown until the next sign-in (docs F5).
     */
    fun clearPush() {
        scope.launch {
            // under the registrar's lock: a registration of the reloaded web app must not run in between and
            // then lose its fresh token to this deletion
            PushRegistrar.exclusive {
                // nothing told the server: the gateway is asked to (F5, Ш3)
                SignedOut.mark(this@WolpApp).set(tokenDeleted = false, serverTold = false)
                PushRegistrar.registered = false
                if (wipePush()) SignedOut.mark(this@WolpApp).tokenDeleted()
            }
            runCatching { SignedOut.cleanUp(this@WolpApp) }
        }
    }

    /** Docs F5, Т2 and Ш3: what a sign-out without the network left behind — on start, on return, on network. */
    fun cleanUpAfterSignOut() {
        scope.launch {
            runCatching {
                val signedInAgain = SignedOut.cleanUp(this@WolpApp)
                // Spec 002 §3.4: the registration is repeated until it goes through — the mark stands with a
                // live session, or no registration has succeeded in this process yet.
                val owed = signedInAgain || (!PushRegistrar.registered && !SignedOut.mark(this@WolpApp).signedOut && AppConfig.kratosCookie() != null)
                if (owed) Log.i(TAG, "push: registration repeated → ${PushRegistrar(this@WolpApp).register().name.lowercase()}")
            }
        }
    }

    private fun watchNetwork() {
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = cleanUpAfterSignOut()
            })
        }.onFailure { Log.w(TAG, "signout: no network callback: ${it.javaClass.simpleName}") }
    }

    /** security/11 §3: on Blocked the FCM token goes and nothing push-related stays on the device. False — FCM still has the token. */
    private suspend fun wipePush(): Boolean {
        Notifier(this).cancelAll()
        PushKeys(this).clear()
        NameCache(this).clear()
        EventDedup.shared(this).clear()
        val deleted = withContext(Dispatchers.IO) { Fcm.deleteToken() }
        Log.i(TAG, "wipe: push keys removed, token ${if (deleted) "removed" else "not removed"}")
        return deleted
    }

    companion object {
        const val TAG = "Wolp"
    }
}
