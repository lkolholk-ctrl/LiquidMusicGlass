pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.google.devtools.ksp") version "2.3.7"
        id("org.jetbrains.kotlin.android") version "2.3.7"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LiquidMusicGlass"
include(":app")