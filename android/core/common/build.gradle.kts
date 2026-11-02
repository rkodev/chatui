plugins {
    id("chatui.kotlin.jvm")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
}

// Pure Kotlin JVM modules expose `test`, not the Android-style task name used
// by the M0-04.2 verification commands. Keep an alias so both names work.
tasks.register("testDebugUnitTest") {
    dependsOn("test")
}
