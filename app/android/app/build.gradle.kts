// org.wolpmessenger (S1). Build rules follow the S2 reference module wolp-device-trust/pilot (S2 §7.3):
//   product — buildKind RELEASE, versionCode MMmmpp with pp 00–89, dry run impossible;
//   pilot   — buildKind PILOT,   versionCode pp 90–99 (below the next release), RASP dry run;
//   debug build type — buildKind DEBUG (only honoured while debuggable), stand host allowed.
// Both release variants are R8-minified and signed with the release key; verify<Variant>BuildKind
// runs before every release build.
//
// Configuration — Gradle properties (-P…, ~/.gradle/gradle.properties, or ../stand.properties for the stand):
//   wolp.enrollUrl, wolp.appUrl          https://enroll.<host>, https://app.<host> (no path; plan A4)
//   wolp.serverPins                      sha256/…,sha256/… — primary + backup server key (≥ 2); one source
//                                        for DeviceTrustConfig.serverPins and the NSC <pin-set> (S1 §4.3)
//   wolp.extraTrustAnchor                PEM file trusted for the app hosts in DEBUG builds only (stand root)
//   wolp.uiHost                          host name of the packaged UI's local origin https://<uiHost> (security/09):
//                                        same site as the app host (the Kratos cookie must reach the APIs),
//                                        never a host:443 the app has to reach over the network
//   wolp.uiDist                          messenger-ui app build (`npm run build:app`, VITE_API_ORIGIN = wolp.appUrl);
//                                        default ../../../messenger/front/dist-app, i.e. a sibling clone
//   wolp.expectedSigningDigests          sha256 hex of the release signing certificate(s), comma separated
//   wolp.signing.{storeFile,storePassword,keyAlias,keyPassword}
//   wolp.productVersionCode / wolp.pilotVersionCode, wolp.minWebViewMajor
//   wolp.e2eeRoot                        PEM file: root certificate(s) of the device CA of this contour — secret chats
//                                        accept a peer's key only with a device certificate under it
//   wolp.e2eeIssuers                     sha256 hex of the public key (SPKI) of each CA that issues device
//                                        certificates, comma separated. Required for a release build; both unset in a
//                                        debug build → the e2ee* bridge calls answer UNAVAILABLE
//   wolp.googleServices                  google-services.json of the Firebase project (S1 §7.1, FCM only). Read at
//                                        build time into BuildConfig; the file is never committed. Unset → push off
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ------------------------------------------------------------------ configuration
val standProps = Properties().apply {
    rootProject.file("stand.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}
fun prop(name: String): String? = (findProperty(name) as String?) ?: standProps.getProperty(name)

val enrollUrl = prop("wolp.enrollUrl")
val appUrl = prop("wolp.appUrl")
val serverPins = prop("wolp.serverPins")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
val extraTrustAnchor = prop("wolp.extraTrustAnchor")?.let { rootProject.file(it) }
val uiHost = prop("wolp.uiHost")?.trim()?.lowercase()
val uiDist = rootProject.file(prop("wolp.uiDist") ?: "../../../messenger/front/dist-app")
val e2eeRoot = prop("wolp.e2eeRoot")?.let { rootProject.file(it) }
val e2eeIssuers = prop("wolp.e2eeIssuers")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
val e2eeRootBase64 = e2eeRoot?.takeIf { it.isFile }?.let { Base64.getEncoder().encodeToString(it.readBytes()) }.orEmpty()
val minWebViewMajor = prop("wolp.minWebViewMajor")?.toInt() ?: 113

/** FCM options from google-services.json (no google-services plugin: only messaging is initialised, S1 §7.1). */
data class Fcm(val projectId: String, val appId: String, val apiKey: String, val senderId: String)

val fcm: Fcm? = prop("wolp.googleServices")?.let { path ->
    val f = rootProject.file(path)
    check(f.isFile) { "wolp.googleServices: no file $f" }
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
    val project = json["project_info"] as Map<String, Any?>
    val client = (json["client"] as List<Map<String, Any?>>).firstOrNull {
        ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "org.wolpmessenger"
    } ?: error("wolp.googleServices: no client for org.wolpmessenger in $f")
    Fcm(
        projectId = project["project_id"] as String,
        appId = (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String,
        apiKey = ((client["api_key"] as List<Map<String, Any?>>).first())["current_key"] as String,
        senderId = project["project_number"] as String,
    )
}

fun configProblems(): List<String> = buildList {
    for ((n, v) in listOf("wolp.enrollUrl" to enrollUrl, "wolp.appUrl" to appUrl)) {
        if (v == null) { add("$n is not set (see app/README.md)"); continue }
        val u = URI(v)
        if (u.scheme != "https" || u.host.isNullOrEmpty() || !(u.path.isNullOrEmpty() || u.path == "/")) add("$n must be https://host[:port] without a path: $v")
    }
    if (serverPins.size < 2 || serverPins.any { !it.startsWith("sha256/") }) add("wolp.serverPins: ≥ 2 values sha256/<base64> (primary + backup)")
    if (extraTrustAnchor != null && !extraTrustAnchor.isFile) add("wolp.extraTrustAnchor: no file $extraTrustAnchor")
    addAll(uiHostProblems())
    if (e2eeRoot != null && !e2eeRoot.isFile) add("wolp.e2eeRoot: no file $e2eeRoot")
    if ((e2eeRoot == null) != e2eeIssuers.isEmpty()) add("wolp.e2eeRoot and wolp.e2eeIssuers go together")
    if (e2eeIssuers.any { !Regex("^[0-9a-f]{64}$").matches(it) }) add("wolp.e2eeIssuers: sha256 of the issuer's public key, 64 hex digits each")
    if (!uiDist.resolve("index.html").isFile) add("wolp.uiDist: no index.html in $uiDist (messenger-ui: VITE_API_ORIGIN=<wolp.appUrl> npm run build:app)")
}

/** Last two labels: enough for the hosts we use (no multi-label public suffixes such as co.uk). */
fun siteOf(host: String): String = if (host == "localhost" || host.all { it.isDigit() || it == '.' }) host else host.split('.').takeLast(2).joinToString(".")

/** security/09 §5: the packaged UI's origin https://<uiHost> must be same-site with app and not shadow a real host. */
fun uiHostProblems(): List<String> = buildList {
    if (uiHost == null) { add("wolp.uiHost is not set (stand: localhost; see app/README.md)"); return@buildList }
    if (!Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$").matches(uiHost)) add("wolp.uiHost must be a bare host name: $uiHost")
    val app = appUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return@buildList
    if (siteOf(uiHost) != siteOf(app.host)) add("wolp.uiHost $uiHost is not same-site with ${app.host}: the Kratos session cookie would not reach the APIs")
    // Capacitor serves https://<uiHost> (port 443) from the APK: a host the app reaches on 443 would vanish
    for (u in listOfNotNull(appUrl, enrollUrl).mapNotNull { runCatching { URI(it) }.getOrNull() }) {
        if (u.host == uiHost && (u.port == -1 || u.port == 443)) add("wolp.uiHost $uiHost would shadow $u (choose a host the app never reaches over the network)")
    }
}

/** One place for the S2 §7.3 parameters of each flavor; BuildConfig and the verification read it. */
data class Channel(val buildKind: String, val versionCode: Int, val dryRun: Boolean)

val channels = mapOf(
    "product" to Channel("RELEASE", prop("wolp.productVersionCode")?.toInt() ?: 10000, dryRun = false),
    "pilot" to Channel("PILOT", prop("wolp.pilotVersionCode")?.toInt() ?: 9990, dryRun = true),
)

val signingProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { prop("wolp.signing.$it") }
val hasSigning = signingProps.values.all { it != null }

/** SHA-256 (hex) of the signing certificate in the keystore, as PackageManager reports it. */
fun signerDigest(): String? = if (!hasSigning) null else {
    val ks = KeyStore.getInstance(File(signingProps.getValue("storeFile")!!), signingProps.getValue("storePassword")!!.toCharArray())
    val cert = checkNotNull(ks.getCertificate(signingProps.getValue("keyAlias"))) { "no certificate '${signingProps["keyAlias"]}' in the keystore" }
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}

// Pinned explicitly for S1 (unlike the pilot reference, never derived from the keystore at hand).
val expectedDigests: List<String> = prop("wolp.expectedSigningDigests")?.split(',')
    ?.map { it.trim().lowercase().replace(":", "") }?.filter { it.isNotEmpty() }.orEmpty()

fun versionName(vc: Int, pilot: Boolean) =
    "%d.%d.%d".format(vc / 10000, vc / 100 % 100, vc % 100) + if (pilot) "-pilot" else ""

android {
    namespace = "org.wolpmessenger"
    compileSdk = 36
    // the app strips libwolpdt.so with ITS NDK: without an installed one the .so ships with debug info (API.md §1)
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.wolpmessenger"
        minSdk = 33
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ENROLL_URL", "\"${enrollUrl.orEmpty()}\"")
        buildConfigField("String", "APP_URL", "\"${appUrl.orEmpty()}\"")
        buildConfigField("String", "UI_HOST", "\"${uiHost.orEmpty()}\"")
        buildConfigField("String", "SERVER_PINS", "\"${serverPins.joinToString(",")}\"")
        buildConfigField("String", "EXPECTED_SIGNING_DIGESTS", "\"${expectedDigests.joinToString(",")}\"")
        buildConfigField("int", "MIN_WEBVIEW_MAJOR", minWebViewMajor.toString())
        // the PEM text goes in as base64: a BuildConfig string cannot hold line breaks
        buildConfigField("String", "E2EE_ROOT", "\"$e2eeRootBase64\"")
        buildConfigField("String", "E2EE_ISSUERS", "\"${e2eeIssuers.joinToString(",")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${fcm?.projectId.orEmpty()}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${fcm?.appId.orEmpty()}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${fcm?.apiKey.orEmpty()}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${fcm?.senderId.orEmpty()}\"")
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }

    flavorDimensions += "channel"
    productFlavors {
        channels.forEach { (name, c) ->
            create(name) {
                dimension = "channel"
                versionCode = c.versionCode
                versionName = versionName(c.versionCode, name == "pilot")
                buildConfigField("boolean", "RASP_DRY_RUN", c.dryRun.toString())
            }
        }
    }

    signingConfigs {
        if (hasSigning) create("release") {
            storeFile = File(signingProps.getValue("storeFile")!!)
            storePassword = signingProps.getValue("storePassword")
            keyAlias = signingProps.getValue("keyAlias")
            keyPassword = signingProps.getValue("keyPassword")
            enableV1Signing = false // minSdk 33: v2 + v3 (S1 §9)
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // phones and the emulator
        }
        release {
            // libsignal adds about 10 MB per ABI; every phone of the project is 64-bit ARM (specs/002 FR-036)
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true // libsignal's AAR demands it (wolp-device-trust docs/API.md §1)
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
        // libsignal: its test library and its desktop builds must not reach the APK (over 200 MB otherwise)
        jniLibs.excludes += "**/libsignal_jni_testing.so"
        resources.excludes += setOf("libsignal_jni*.so", "libsignal_jni*.dylib", "signal_jni*.dll")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":capacitor-android"))
    implementation("org.wolpmessenger:devicetrust") // includeBuild (settings.gradle.kts)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:26.0.1") // S1 §7.1: only messaging
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814") // real org.json for JVM tests (android.jar has stubs)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// ------------------------------------------------------------------ network_security_config (S1 §4.3)
/**
 * Generated from wolp.serverPins and the app hosts, so the WebView (NSC) and native clients
 * (DeviceTrustConfig.serverPins) pin the same keys. No <pin-set expiration>: an expired set would
 * silently switch pinning off. System CAs only; user CAs are never trusted. The extra trust anchor
 * (stand root) exists in debug variants only.
 */
abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input abstract val hosts: ListProperty<String>
    @get:Input abstract val pins: ListProperty<String>
    @get:Optional @get:InputFile abstract val extraAnchor: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val anchor = extraAnchor.orNull?.asFile
        if (anchor != null) File(out, "raw").apply { mkdirs() }.resolve("extra_trust_anchor.pem").writeBytes(anchor.readBytes())
        val pinXml = pins.get().joinToString("\n") { p ->
            "            <pin digest=\"SHA-256\">${p.removePrefix("sha256/")}</pin>"
        }
        val domains = hosts.get().joinToString("\n") { "        <domain includeSubdomains=\"false\">$it</domain>" }
        val extra = if (anchor != null) "\n            <certificates src=\"@raw/extra_trust_anchor\" />" else ""
        File(out, "xml").apply { mkdirs() }.resolve("network_security_config.xml").writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- GENERATED by :app:generate*NetworkSecurityConfig from wolp.* properties — do not edit. -->
            |<network-security-config>
            |    <base-config cleartextTrafficPermitted="false">
            |        <trust-anchors>
            |            <certificates src="system" />
            |        </trust-anchors>
            |    </base-config>
            |    <domain-config cleartextTrafficPermitted="false">
            |$domains
            |        <trust-anchors>
            |            <certificates src="system" />$extra
            |        </trust-anchors>
            |        <pin-set>
            |$pinXml
            |        </pin-set>
            |    </domain-config>
            |</network-security-config>
            |""".trimMargin()
        )
    }
}

// ------------------------------------------------------------------ packaged UI (security/09-ui-in-apk.md)
/**
 * Copies the messenger-ui app build into assets/public, where Capacitor serves it as https://<uiHost>/.
 * Refuses a build made for another API origin (the bundle carries VITE_API_ORIGIN as a string) and
 * leaves out what the APK must not ship: service worker scripts, source maps, bundle reports.
 */
abstract class BundleWebUi : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val dist: DirectoryProperty
    @get:Input abstract val apiOrigin: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val src = dist.get().asFile
        val out = outputDir.get().asFile.apply { deleteRecursively() }
        val public = File(out, "public").apply { mkdirs() }
        check(File(src, "index.html").isFile) { "no index.html in $src" }
        val origin = apiOrigin.get()
        val scripts = File(src, "assets").listFiles { f -> f.name.endsWith(".js") }.orEmpty()
        check(scripts.any { origin in it.readText() }) {
            "the UI in $src was not built for $origin (messenger-ui: VITE_API_ORIGIN=$origin npm run build:app)"
        }
        val skip = Regex("""(^|/)(push-sw\.js|app-sw\.js|sw\.js|workbox-[^/]*\.js|registerSW\.js|manifest\.webmanifest|stats\.html)$|\.map$""")
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src).invariantSeparatorsPath
            if (!skip.containsMatchIn(rel)) f.copyTo(File(public, rel), overwrite = true)
        }
        logger.lifecycle("${name}: UI from $src (API $origin)")
    }
}

val releaseDebuggable = android.buildTypes.getByName("release").isDebuggable
val validateConfig = tasks.register("validateWolpConfig") {
    group = "verification"
    description = "wolp.* hosts and pins are present and well-formed"
    doLast {
        val problems = configProblems()
        check(problems.isEmpty()) { "validateWolpConfig:\n  " + problems.joinToString("\n  ") }
    }
}

androidComponents {
    onVariants { variant ->
        val flavor = checkNotNull(variant.flavorName)
        val channel = channels.getValue(flavor)
        val debug = variant.buildType == "debug"
        val cap = variant.name.replaceFirstChar { it.uppercase() }

        variant.buildConfigFields?.put("BUILD_KIND", com.android.build.api.variant.BuildConfigField("String", "\"${if (debug) "DEBUG" else channel.buildKind}\"", null))

        val hosts = listOfNotNull(enrollUrl, appUrl).mapNotNull { runCatching { URI(it).host }.getOrNull() }.distinct()
        val nsc = tasks.register<GenerateNetworkSecurityConfig>("generate${cap}NetworkSecurityConfig") {
            this.hosts.set(hosts)
            pins.set(serverPins)
            if (debug && extraTrustAnchor != null) extraAnchor.set(extraTrustAnchor)
            outputDir.set(layout.buildDirectory.dir("generated/wolp-nsc/${variant.name}"))
            dependsOn(validateConfig)
        }
        variant.sources.res?.addGeneratedSourceDirectory(nsc, GenerateNetworkSecurityConfig::outputDir)

        val ui = tasks.register<BundleWebUi>("bundle${cap}WebUi") {
            dist.set(uiDist)
            apiOrigin.set(appUrl.orEmpty().trimEnd('/'))
            outputDir.set(layout.buildDirectory.dir("generated/wolp-ui/${variant.name}"))
            dependsOn(validateConfig)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(ui, BundleWebUi::outputDir)

        if (debug) return@onVariants
        // ---------------------------------------------- S2 §7.3 build check (as wolp-device-trust/pilot)
        val versionCode = variant.outputs.single().versionCode
        val signer = signerDigest()
        val expected = expectedDigests
        val verify = tasks.register("verify${cap}BuildKind") {
            group = "verification"
            description = "S2 §7.3: build kind, versionCode range, dry run, debuggable, signer and hosts of ${variant.name}"
            doLast {
                val vc = checkNotNull(versionCode.orNull) { "no versionCode" }
                val pilotRange = vc % 100 >= 90
                val problems = buildList {
                    when (flavor) {
                        "product" -> {
                            if (channel.buildKind != "RELEASE") add("product must be buildKind RELEASE, is ${channel.buildKind}")
                            if (pilotRange) add("product versionCode $vc is in the pilot range (last two digits 90–99)")
                            if (channel.dryRun) add("product must not enable raspDryRun")
                        }
                        "pilot" -> {
                            if (channel.buildKind != "PILOT") add("pilot must be buildKind PILOT, is ${channel.buildKind}")
                            if (!pilotRange) add("pilot versionCode $vc is outside the pilot range (last two digits 90–99)")
                        }
                        else -> add("unknown flavor $flavor")
                    }
                    if (releaseDebuggable) add("release build type is debuggable")
                    if (signer == null) add("release signing not configured (wolp.signing.*)")
                    else if (signer !in expected) add("signing certificate $signer is not in wolp.expectedSigningDigests $expected")
                    if (expected.isEmpty()) add("wolp.expectedSigningDigests empty: DeviceTrust.init() rejects a non-debuggable app without them")
                    for (h in hosts) if (h == "localhost" || h == "127.0.0.1") add("release build points at the stand host $h")
                    if (fcm == null) add("wolp.googleServices not set: a release build without push (S1 §7.1)")
                    if (e2eeRoot == null || e2eeIssuers.isEmpty()) add("wolp.e2eeRoot / wolp.e2eeIssuers not set: the packaged UI encrypts secret chats and calls through the library only")
                }
                check(problems.isEmpty()) { "verify${cap}BuildKind:\n  " + problems.joinToString("\n  ") }
                logger.lifecycle("verify${cap}BuildKind: OK (${channel.buildKind}, versionCode $vc, dryRun ${channel.dryRun}, signer ${signer?.take(16)}…)")
            }
        }
        tasks.configureEach { if (name == "pre${cap}Build") dependsOn(verify) }
    }
}
