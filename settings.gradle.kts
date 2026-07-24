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
        // Пропатченный media3 (форк-репо media3-lmg): CI скачивает zip релиза и
        // распаковывает в ./media3-m2. Content-фильтр — только androidx.media3,
        // остальное из google()/mavenCentral(). Версия форка (…-lmg1) уникальна.
        maven {
            url = uri("media3-m2")
            content { includeGroup("androidx.media3") }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LiquidMusicGlass"
include(":app")