plugins {
    `kotlin-dsl`
}

group = "dev.chatui.buildlogic"

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
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "chatui.android.application"
            implementationClass = "dev.chatui.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "chatui.android.library"
            implementationClass = "dev.chatui.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "chatui.android.library.compose"
            implementationClass = "dev.chatui.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "chatui.android.feature"
            implementationClass = "dev.chatui.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("kotlinJvm") {
            id = "chatui.kotlin.jvm"
            implementationClass = "dev.chatui.buildlogic.KotlinJvmConventionPlugin"
        }
    }
}
