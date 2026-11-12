// S1 app (org.wolpmessenger). Capacitor's native bridge comes straight from npm (../node_modules,
// `npm ci` in app/); there is no `cap sync`: the Capacitor config is built in code from BuildConfig
// (MainActivity), so hosts and pins have one source — the wolp.* Gradle properties.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // capacitor-android declares its own repositories (npm sources are not ours to edit)
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // libsignal (secret chats, comes with wolp-device-trust) is published only here
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            content { includeGroup("org.signal") }
        }
    }
}
rootProject.name = "wolp-android"
include(":app")
include(":capacitor-android")
project(":capacitor-android").projectDir = file("../node_modules/@capacitor/android/capacitor")

// S2 library (plan A2): git submodule pinned to a wolp-device-trust commit; -Pwolp.deviceTrustDir=<clone>
// builds against a local working copy instead.
val deviceTrustDir = providers.gradleProperty("wolp.deviceTrustDir").orNull ?: "third_party/wolp-device-trust"
check(file("$deviceTrustDir/settings.gradle.kts").exists()) {
    "wolp-device-trust not found in $deviceTrustDir: git submodule update --init (or -Pwolp.deviceTrustDir=…)"
}
includeBuild(deviceTrustDir) {
    dependencySubstitution {
        substitute(module("org.wolpmessenger:devicetrust")).using(project(":devicetrust"))
    }
}
