plugins {
    id("chatui.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.chatui.core.datastore"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
}
