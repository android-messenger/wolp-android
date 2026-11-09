buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // same versions as wolp-device-trust (composite build) and Capacitor 8.6
        classpath("com.android.tools.build:gradle:8.13.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
    }
}

// read by capacitor-android's build.gradle (rootProject.ext.*)
extra["minSdkVersion"] = 33
extra["compileSdkVersion"] = 36
extra["targetSdkVersion"] = 35
