plugins {
    id("chatui.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.chatui.core.backup"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
