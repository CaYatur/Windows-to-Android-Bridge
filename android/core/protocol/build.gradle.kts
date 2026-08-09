plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Deliberately a plain JVM module, not an Android library: the protocol has no
// Android dependencies, and keeping it here means the cross-language vectors
// run as fast local unit tests instead of needing a device.
//
// The constraint that follows: nothing in here may use an API missing on
// minSdk 26. That is why HKDF is hand-rolled and the P-256 curve parameters are
// written out rather than fetched from AlgorithmParameters.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // Decoding a pairing QR out of a screenshot, for hardware bring-up only.
    testImplementation(libs.zxing.javase)
}

tasks.withType<Test> {
    testLogging { events("passed", "failed", "skipped") }
}
