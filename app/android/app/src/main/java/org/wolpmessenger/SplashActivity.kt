package org.wolpmessenger

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.EnrollResult
import org.wolpmessenger.launch.AndroidProbes
import org.wolpmessenger.launch.LaunchGate
import org.wolpmessenger.launch.Screen
import org.wolpmessenger.ui.BlockedScreen
import org.wolpmessenger.ui.LoginScreen
import org.wolpmessenger.ui.OfflineScreen
import org.wolpmessenger.ui.OnboardingScreen
import org.wolpmessenger.ui.UnsupportedScreen
import org.wolpmessenger.ui.UpdateScreen
import org.wolpmessenger.ui.WaitScreen
import org.wolpmessenger.ui.WebViewOutdatedScreen
import org.wolpmessenger.ui.WrongUserScreen

/**
 * Launcher and the S1 §3 order. app.<host> is never loaded from here: only [Screen.Messenger]
 * starts MainActivity (ADR-0001 п. 3). The order is re-run on every start of this activity, on
 * every S2 state change, after login and after an enrollment attempt.
 */
class SplashActivity : ComponentActivity() {
    private val gate by lazy { LaunchGate(AndroidProbes(applicationContext)) }
    private var screen by mutableStateOf<Screen>(Screen.Wait)
    private var evaluation: Job? = null
    private var loginOpened = false

    // onboarding (S1 §3.2)
    private var enrollBusy by mutableStateOf(false)
    private var enrollMessage by mutableStateOf<String?>(null)
    private var autoRetried = false

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { evaluate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        setContent {
            when (val s = screen) {
                Screen.Wait -> WaitScreen()
                Screen.WebViewOutdated -> WebViewOutdatedScreen(::evaluate)
                is Screen.Blocked -> BlockedScreen(s.reason) { finishAndRemoveTask() }
                is Screen.Unsupported -> UnsupportedScreen(s.reasons) { finishAndRemoveTask() }
                is Screen.Update -> UpdateScreen(s.kind, ::evaluate)
                is Screen.Offline -> OfflineScreen(s.serverUnverified, ::evaluate)
                Screen.Login -> LoginScreen(::openLogin)
                is Screen.Onboarding -> OnboardingScreen(s.reEnroll, enrollBusy, enrollMessage, ::enroll)
                Screen.Messenger -> WaitScreen()
                Screen.WrongUser -> WrongUserScreen(onLogin = { loginOpened = false; evaluate() }, onClose = { finishAndRemoveTask() })
            }
        }
        // every S2 state change re-runs the order (S1 §3: renewal, block, revocation)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DeviceTrust.state.drop(1).collect { evaluate() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // MainActivity hands over with a reason when the order alone would not show the right screen
        val reason = intent.getStringExtra(EXTRA_REASON)
        if (reason != null) {
            intent.removeExtra(EXTRA_REASON) // once: the next start runs the order again
            when (reason) {
                // the WebView failed TLS to our host; the order's pinned ping may well pass (S1 §4.3)
                REASON_SERVER_UNVERIFIED -> return show(Screen.Offline(serverUnverified = true))
                REASON_WRONG_USER -> return show(Screen.WrongUser)
            }
        }
        evaluate()
    }

    private fun evaluate() {
        evaluation?.cancel()
        evaluation = lifecycleScope.launch {
            val s = gate.evaluate()
            Log.i(WolpApp.TAG, "launch gate → $s")
            show(s)
        }
    }

    private fun show(s: Screen) {
        if (s !is Screen.Onboarding) { enrollMessage = null; autoRetried = false }
        onboardingWindow(s is Screen.Onboarding)
        screen = s
        when (s) {
            Screen.Messenger -> {
                startActivity(Intent(this, MainActivity::class.java).setData(intent.data))
                finish()
            }
            // open the login page once by itself; afterwards the button does (no loop on "back")
            Screen.Login -> if (!loginOpened) openLogin()
            else -> Unit
        }
    }

    private fun openLogin() {
        loginOpened = true
        login.launch(Intent(this, LoginActivity::class.java))
    }

    /** S1 §3.2: FLAG_SECURE (even in debug) and no autofill while the onboarding password is on screen. */
    private fun onboardingWindow(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else if (BuildConfig.DEBUG) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill =
            if (on) View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS else View.IMPORTANT_FOR_AUTOFILL_AUTO
    }

    companion object {
        const val EXTRA_REASON = "org.wolpmessenger.REASON"
        const val REASON_SERVER_UNVERIFIED = "server_unverified"
        const val REASON_WRONG_USER = "wrong_user"
    }

    private fun enroll(password: CharArray) {
        val cookie = AppConfig.kratosCookie()
        if (cookie == null) { password.fill('\u0000'); show(Screen.Login); return }
        enrollBusy = true
        enrollMessage = null
        lifecycleScope.launch {
            // keep a copy only for the one automatic retry on Transient; S2 wipes the array it gets
            val retryCopy = password.copyOf()
            val r = try { DeviceTrust.enroll(cookie, password) } finally { password.fill('\u0000') }
            Log.i(WolpApp.TAG, "enroll → ${r::class.simpleName}")
            enrollBusy = false
            when (r) {
                is EnrollResult.Ok -> { retryCopy.fill('\u0000'); evaluate() }
                is EnrollResult.WrongPassword -> enrollMessage = getString(R.string.enroll_wrong_password, r.attemptsLeft)
                EnrollResult.PasswordBurned, EnrollResult.PasswordExpired, EnrollResult.NoPassword ->
                    enrollMessage = getString(R.string.enroll_password_invalid)
                is EnrollResult.PolicyRejected -> enrollMessage = getString(R.string.enroll_policy_rejected, r.reason)
                is EnrollResult.UpdateRequired -> show(Screen.Update(r.kind)) // password not burned
                is EnrollResult.Unsupported -> show(Screen.Unsupported(r.reasons))
                is EnrollResult.Blocked -> show(Screen.Blocked(r.reason))
                EnrollResult.NoSession -> { loginOpened = false; show(Screen.Login) }
                is EnrollResult.Transient -> {
                    enrollMessage = getString(R.string.enroll_offline)
                    if (!autoRetried) {
                        autoRetried = true
                        delay((r.retryAfter?.toMillis() ?: 3_000L).coerceIn(1_000L, 60_000L))
                        if (screen is Screen.Onboarding) { enroll(retryCopy); return@launch }
                    }
                }
            }
            retryCopy.fill('\u0000')
        }
    }
}
