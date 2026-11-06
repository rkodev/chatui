import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.gradle.api.GradleException
import org.gradle.api.tasks.Delete

plugins {
    id("chatui.android.application")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
}

// --- Release signing -------------------------------------------------------
// Signing material comes only from environment/CI values. Nothing is read from
// the repository and no value is ever printed.
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val keystorePath = env("ANDROID_KEYSTORE_PATH")
val keystoreBase64 = env("ANDROID_KEYSTORE_BASE64")
val releaseStorePassword = env("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = env("ANDROID_KEY_ALIAS")
val releaseKeyPassword = env("ANDROID_KEY_PASSWORD")
val releaseSigningRequired =
    env("RELEASE_SIGNING_REQUIRED")?.equals("true", ignoreCase = true) == true

val missingSigningValues = buildList {
    if (keystorePath == null && keystoreBase64 == null) {
        add("ANDROID_KEYSTORE_PATH or ANDROID_KEYSTORE_BASE64")
    }
    if (releaseStorePassword == null) add("ANDROID_KEYSTORE_PASSWORD")
    if (releaseKeyAlias == null) add("ANDROID_KEY_ALIAS")
    if (releaseKeyPassword == null) add("ANDROID_KEY_PASSWORD")
}

if (releaseSigningRequired && missingSigningValues.isNotEmpty()) {
    throw GradleException(
        "Release signing is required (RELEASE_SIGNING_REQUIRED=true) but these " +
            "environment values are missing: ${missingSigningValues.joinToString(", ")}. " +
            "Provide them as CI secrets, or set RELEASE_SIGNING_REQUIRED=false for an " +
            "unsigned local release build. Never commit a keystore or password.",
    )
}

val releaseSigningAvailable = missingSigningValues.isEmpty()

// Decode a base64 keystore into a temporary file outside the repository. The
// file is removed by the cleanupReleaseKeystore finalizer and on JVM exit.
val temporaryKeystore: File? =
    if (releaseSigningAvailable && keystorePath == null && keystoreBase64 != null) {
        val decoded = Base64.getMimeDecoder().decode(keystoreBase64)
        Files.createTempFile("chatui-release-", ".keystore").toFile().apply {
            writeBytes(decoded)
            setReadable(false, false)
            setReadable(true, true)
            setWritable(true, true)
            setExecutable(false, false)
            deleteOnExit()
        }
    } else {
        null
    }

val releaseStoreFile: File? = when {
    !releaseSigningAvailable -> null
    keystorePath != null -> file(keystorePath)
    else -> temporaryKeystore
}

if (keystorePath != null && releaseStoreFile != null && !releaseStoreFile.isFile) {
    throw GradleException(
        "ANDROID_KEYSTORE_PATH does not point to a readable keystore file. " +
            "Provide a valid path or use ANDROID_KEYSTORE_BASE64 instead.",
    )
}

// --- Versioning ------------------------------------------------------------
// versionCode is explicit in CI, otherwise the git commit count (monotonic).
// versionName is explicit, otherwise an exact semantic tag, otherwise a dev
// pre-release. See docs/operations/M3/release-signing.md.
fun gitOutput(vararg command: String): String? = try {
    val process = ProcessBuilder(*command)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) output.takeIf { it.isNotEmpty() } else null
} catch (_: Exception) {
    null
}

val explicitVersionCode = env("ANDROID_VERSION_CODE")
val resolvedVersionCode: Int = if (explicitVersionCode != null) {
    explicitVersionCode.toIntOrNull()?.takeIf { it > 0 }
        ?: throw GradleException(
            "ANDROID_VERSION_CODE must be a positive integer. Fix the CI value; " +
                "do not commit a version number to the build file.",
        )
} else {
    gitOutput("git", "rev-list", "--count", "HEAD")?.toIntOrNull()?.takeIf { it > 0 } ?: 1
}

val semanticTag = gitOutput("git", "describe", "--tags", "--exact-match")
    ?.removePrefix("v")
    ?.takeIf { Regex("""^\d+\.\d+\.\d+([-+].*)?$""").matches(it) }
val resolvedVersionName: String = env("ANDROID_VERSION_NAME") ?: semanticTag ?: "0.1.0-dev"

android {
    namespace = "dev.chatui"

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                this.keyAlias = releaseKeyAlias
                this.keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "dev.chatui"
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
    }

    buildTypes {
        release {
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty(
                    "roborazzi.record.filePathStrategy",
                    "relativePathFromRoborazziContextOutputDirectory",
                )
            }
        }
    }

    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/kotlin")
    }
}

// Keep the generated profile in source control so release builds always ship
// the same startup profile. Regenerate with
// `:app:generateBaselineProfile` on a connected device.
baselineProfile {
    saveInSrc = true
}

// Remove a decoded base64 keystore as soon as the release package tasks finish.
val tempKeystore = temporaryKeystore
if (tempKeystore != null) {
    val cleanupReleaseKeystore = tasks.register<Delete>("cleanupReleaseKeystore") {
        delete(tempKeystore)
    }
    tasks.matching {
        it.name in setOf("packageRelease", "packageReleaseBundle", "packageReleaseUniversalApk")
    }.configureEach {
        finalizedBy(cleanupReleaseKeystore)
    }
    // Also remove the decoded keystore when a build fails before packaging.
    gradle.buildFinished {
        tempKeystore.delete()
    }
}

// Keep committed goldens next to the screenshot tests so
// `:app:verifyRoborazziDebug` compares against source-controlled images.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:providers"))
    implementation(libs.androidx.room.runtime)
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:conversations"))
    implementation(project(":feature:providers"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:backup"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
