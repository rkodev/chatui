plugins {
    id("chatui.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.chatui.core.backup"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.truth)
}
