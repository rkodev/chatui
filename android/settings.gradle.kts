pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ChatUI"

include(":app")
include(":baselineprofile")
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:datastore")
include(":core:backup")
include(":core:network")
include(":core:providers")
include(":feature:onboarding")
include(":feature:chat")
include(":feature:conversations")
include(":feature:providers")
include(":feature:settings")
include(":feature:backup")

includeBuild("build-logic")
