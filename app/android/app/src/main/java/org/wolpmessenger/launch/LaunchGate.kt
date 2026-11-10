package org.wolpmessenger.launch

import org.wolpmessenger.devicetrust.api.BlockReason
import org.wolpmessenger.devicetrust.api.PrecheckResult
import org.wolpmessenger.devicetrust.api.TrustState
import org.wolpmessenger.devicetrust.api.UpdateKind

/** What the launcher shows (S1 §3). [Messenger] is the only result that lets app.<host> load. */
sealed interface Screen {
    data object Wait : Screen
    data object WebViewOutdated : Screen
    data class Blocked(val reason: BlockReason?) : Screen
    data class Unsupported(val reasons: List<String>) : Screen
    /** [kind] null = mandatory app update: the gateway's minimum version (S1 §8 manifest after S1-8). */
    data class Update(val kind: UpdateKind?) : Screen
    data class Offline(val serverUnverified: Boolean = false) : Screen
    data object Login : Screen
    data class Onboarding(val reEnroll: Boolean) : Screen
    data object Messenger : Screen
    /** Not from the order: the pinned `whoami` confirmed USER_MISMATCH and Kratos was logged out (S1 §9). */
    data object WrongUser : Screen
}

/** Everything the S1 §3 order looks at; Android and the network live behind it (tests use fakes). */
interface LaunchProbes {
    fun trustState(): TrustState
    /** Step 1: System WebView ≥ minWebViewMajor and DOCUMENT_START_SCRIPT + WEB_MESSAGE_LISTENER. */
    fun webViewUsable(): Boolean
    /** Steps 2/3 before enrollment (S2 keeps the result cached; `Compromised` has already blocked). */
    suspend fun precheck(): PrecheckResult
    /** Step 4: versionCode < the gateway's minimum (`X-Device-Min-App-Version`; S1 §8 manifests after S1-8). */
    suspend fun mandatoryUpdate(): Boolean
    /** Step 5: the gateway answers — enroll manifest before enrollment, pinned ping after. */
    suspend fun reachable(enrolled: Boolean): Reachability
    /** Step 6: Kratos cookie present and `whoami` = 200. */
    suspend fun hasSession(): Boolean
    /** Step 8: S2 renewal/recovery; returns the new state. */
    suspend fun ensureFresh(): TrustState
}

enum class Reachability { OK, OFFLINE, SERVER_UNVERIFIED }

/**
 * S1 §3: the checks run strictly in order and the first that fires decides the screen. Pure logic —
 * no Android types — so each step and the order are JVM-tested (S1 §10 «Запуск»).
 */
class LaunchGate(private val p: LaunchProbes) {

    suspend fun evaluate(): Screen {
        var refreshed = false
        while (true) {
            // 0
            val state = p.trustState()
            if (state is TrustState.Uninitialized) return Screen.Wait
            // 1
            if (!p.webViewUsable()) return Screen.WebViewOutdated
            // 2, 3 — precheck matters only while the device has no certificate (it gates enrollment);
            // an enrolled device is re-checked by RASP and by attestation on every renewal (S2 §7, D7)
            if (state is TrustState.Blocked) return Screen.Blocked(state.reason)
            val enrolled = state !is TrustState.NotEnrolled && state !is TrustState.ReEnrollRequired
            if (!enrolled) when (val pc = p.precheck()) {
                is PrecheckResult.Compromised -> return Screen.Blocked(null)
                is PrecheckResult.Unsupported -> return Screen.Unsupported(pc.reasons.map { it.name })
                is PrecheckResult.Supported -> Unit
            }
            // the precheck may have blocked the device (BLOCK reaction) — the state is authoritative
            p.trustState().let { if (it is TrustState.Blocked) return Screen.Blocked(it.reason) }
            // 4
            if (p.mandatoryUpdate()) return Screen.Update(null)
            // 4a
            if (state is TrustState.UpdateRequired) return Screen.Update(state.kind)
            // 5
            when (p.reachable(enrolled)) {
                Reachability.OFFLINE -> return Screen.Offline()
                Reachability.SERVER_UNVERIFIED -> return Screen.Offline(serverUnverified = true)
                Reachability.OK -> Unit
            }
            // 5a — the ping of step 5 may have brought a new minimum version (S3 §5.8a)
            if (enrolled && p.mandatoryUpdate()) return Screen.Update(null)
            // 6
            if (!p.hasSession()) return Screen.Login
            // 7
            if (!enrolled) return Screen.Onboarding(reEnroll = state is TrustState.ReEnrollRequired)
            // 8 — once; a second pass that still is not Active decides by the table (no loop)
            if (state is TrustState.ExpiredGrace || state is TrustState.RenewalDue) {
                if (!refreshed) {
                    refreshed = true
                    p.ensureFresh()
                    continue
                }
                // RenewalDue still holds a valid certificate: the messenger works while renewal retries
                if (state is TrustState.ExpiredGrace) return Screen.Offline()
            }
            // 9
            return Screen.Messenger
        }
    }
}
