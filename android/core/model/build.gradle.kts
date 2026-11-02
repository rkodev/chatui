plugins {
    id("chatui.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// Pure Kotlin JVM modules expose `test`, not the Android-style task name used
// by the M0-04.2 verification commands. Keep an alias so both names work.
tasks.register("testDebugUnitTest") {
    dependsOn("test")
}
