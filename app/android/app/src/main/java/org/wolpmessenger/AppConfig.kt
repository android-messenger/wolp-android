package org.wolpmessenger

import android.webkit.CookieManager
import org.wolpmessenger.devicetrust.api.BuildKind
import org.wolpmessenger.devicetrust.api.DeviceTrustConfig
import org.wolpmessenger.devicetrust.api.RaspCheck
import org.wolpmessenger.devicetrust.api.RaspPolicy
import org.wolpmessenger.devicetrust.api.RaspReaction

/** Build-time configuration (wolp.* Gradle properties → BuildConfig; one source, plan A4/A5). */
object AppConfig {
    val enrollUrl: String = BuildConfig.ENROLL_URL
    val appUrl: String = BuildConfig.APP_URL
    val serverPins: List<String> = BuildConfig.SERVER_PINS.split(',').filter { it.isNotEmpty() }
    val expectedSigningDigests: List<String> = BuildConfig.EXPECTED_SIGNING_DIGESTS.split(',').filter { it.isNotEmpty() }
    val buildKind: BuildKind = BuildKind.valueOf(BuildConfig.BUILD_KIND)

    /** Web client entry point (ADR-0001 п. 1: server.url has no path, the path is appStartPath). */
    const val START_PATH = "/messenger-ui/"

    fun deviceTrust(): DeviceTrustConfig = DeviceTrustConfig(
        enrollBaseUrl = enrollUrl,
        appBaseUrl = appUrl,
        serverPins = serverPins,
        expectedSigningDigests = expectedSigningDigests,
        cookieProvider = ::kratosCookie,
        buildKind = buildKind,
        raspDryRun = BuildConfig.RASP_DRY_RUN,
        // A debuggable build would block itself on the first RASP pass (API.md §3). The library
        // ignores lowering in RELEASE, so this is inert in product/pilot release builds.
        raspPolicy = if (BuildConfig.DEBUG) {
            RaspPolicy.default()
                .with(RaspCheck.APP_DEBUGGABLE, RaspReaction.REPORT)
                .with(RaspCheck.DEBUGGER, RaspReaction.REPORT)
        } else RaspPolicy.default(),
    )

    /**
     * Kratos session cookie for S2 (S1 §3 `cookieProvider`): app host first, enroll otherwise. One
     * Kratos session covers both hosts (D12), so either jar holds the same cookie once logged in.
     */
    fun kratosCookie(): String? = runCatching {
        val cm = CookieManager.getInstance()
        cm.getCookie(appUrl)?.takeIf { KRATOS_COOKIE in it } ?: cm.getCookie(enrollUrl)?.takeIf { KRATOS_COOKIE in it }
    }.getOrNull()

    const val KRATOS_COOKIE = "ory_kratos_session"
}
