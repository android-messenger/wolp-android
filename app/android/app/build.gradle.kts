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
//   wolp.expectedSigningDigests          sha256 hex of the release signing certificate(s), comma separated
//   wolp.signing.{storeFile,storePassword,keyAlias,keyPassword}
//   wolp.productVersionCode / wolp.pilotVersionCode, wolp.minWebViewMajor
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
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
val minWebViewMajor = prop("wolp.minWebViewMajor")?.toInt() ?: 113

fun configProblems(): List<String> = buildList {
    for ((n, v) in listOf("wolp.enrollUrl" to enrollUrl, "wolp.appUrl" to appUrl)) {
        if (v == null) { add("$n is not set (see app/README.md)"); continue }
        val u = URI(v)
        if (u.scheme != "https" || u.host.isNullOrEmpty() || !(u.path.isNullOrEmpty() || u.path == "/")) add("$n must be https://host[:port] without a path: $v")
    }
    if (serverPins.size < 2 || serverPins.any { !it.startsWith("sha256/") }) add("wolp.serverPins: ≥ 2 values sha256/<base64> (primary + backup)")
    if (extraTrustAnchor != null && !extraTrustAnchor.isFile) add("wolp.extraTrustAnchor: no file $extraTrustAnchor")
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
        buildConfigField("String", "SERVER_PINS", "\"${serverPins.joinToString(",")}\"")
        buildConfigField("String", "EXPECTED_SIGNING_DIGESTS", "\"${expectedDigests.joinToString(",")}\"")
        buildConfigField("int", "MIN_WEBVIEW_MAJOR", minWebViewMajor.toString())
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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin") }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":capacitor-android"))
    implementation("org.wolpmessenger:devicetrust") // includeBuild (settings.gradle.kts)

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")

    testImplementation("junit:junit:4.13.2")
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
                }
                check(problems.isEmpty()) { "verify${cap}BuildKind:\n  " + problems.joinToString("\n  ") }
                logger.lifecycle("verify${cap}BuildKind: OK (${channel.buildKind}, versionCode $vc, dryRun ${channel.dryRun}, signer ${signer?.take(16)}…)")
            }
        }
        tasks.configureEach { if (name == "pre${cap}Build") dependsOn(verify) }
    }
}
