import org.gradle.api.tasks.Sync
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("chatui.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

// Contracts live outside the Android source tree. Copy them into generated
// resource roots so runtime code and unit tests read the checked-in contract
// instead of a hand-maintained Kotlin copy.
val contractsDir = rootProject.file("../contracts")
val generatedContractResources = layout.buildDirectory.dir("generated/contracts/main")
val generatedContractTestResources = layout.buildDirectory.dir("generated/contracts/test")

val syncContractResources = tasks.register<Sync>("syncContractResources") {
    from(contractsDir.resolve("providers.json")) {
        into("contracts")
    }
    into(generatedContractResources)
}

val syncContractTestResources = tasks.register<Sync>("syncContractTestResources") {
    from(contractsDir.resolve("fixtures")) {
        into("contracts/fixtures")
    }
    into(generatedContractTestResources)
}

sourceSets {
    main {
        resources.srcDir(generatedContractResources)
    }
    test {
        resources.srcDir(generatedContractTestResources)
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(syncContractResources)
}

tasks.named<ProcessResources>("processTestResources") {
    dependsOn(syncContractTestResources)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}

// Pure Kotlin JVM modules expose `test`, not the Android-style task name used
// by the M0-04.2 verification commands. Keep an alias so both names work.
tasks.register("testDebugUnitTest") {
    dependsOn("test")
}
