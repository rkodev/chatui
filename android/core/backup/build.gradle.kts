plugins {
    id("chatui.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.chatui.core.backup"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
