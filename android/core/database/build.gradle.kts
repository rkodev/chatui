plugins {
    id("chatui.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.chatui.core.database"

    sourceSets {
        // MigrationTestHelper reads the exported schema from test assets using
        // the database class's canonical name as the asset folder.
        getByName("test").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
