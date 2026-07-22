plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.3.10"
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.liquidmusicglass"

    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.liquidmusicglass"
        minSdk = 29
        targetSdk = 36
        // Выше 20260702 (сборки greeting уже стоят на тест-девайсах с ним):
        // меньший versionCode Android считает даунгрейдом и отклоняет установку
        // поверх с ошибкой «пакет недействителен».
        versionCode = 20260759
        // Метка билда (видно в Профиле внизу). Бампается вручную на заметных
        // сборках, чтобы можно было отличить, какой билд стоит. Динамический
        // git-хэш убран — он ломал конфигурацию Gradle в CI.
        versionName = "21.07.7 landscape-wip"

        // Build native libs only for arm64-v8a (faster builds, smaller APK).
        // Note: won't run on 32-bit (armeabi-v7a) or x86/x86_64 emulators.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // ICM_API_KEY из BuildConfig УДАЛЁН: партнёрский ключ живёт только на
        // сервере-брокере (IcmApi.SERVER_BASE) — в APK секрета нет вообще.
    }

    // Resolve a signing secret from the environment first (CI), then from
    // local.properties for local builds. Never hardcode credentials here.
    fun signingSecret(envName: String, localPropName: String): String {
        System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it }
        val f = rootProject.file("local.properties")
        if (f.exists()) {
            val prefix = "$localPropName="
            f.readLines().find { it.startsWith(prefix) }
                ?.removePrefix(prefix)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return ""
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release-key.jks")
            storePassword = signingSecret("KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "release"
            keyPassword = signingSecret("KEY_PASSWORD", "KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            // Debug uses auto-generated debug signing
        }
        release {
            // Полная защита: R8-минификация + обфускация + ресурс-шринк.
            // Keep-правила — в proguard-rules.pro (JNI-мосты, Room, сериализация,
            // LiteRT сохранены). Диагностика показала, что баг локального аудио
            // был НЕ в R8 (в Oboe-патче + страже кодека, откачены).
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            vcsInfo.include = false
            signingConfig = if (file(System.getenv("KEYSTORE_PATH") ?: "release-key.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.3.11579264"

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt",
                "/META-INF/{AL2.0,LGPL2.1}"
            )
        }
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xlambdas=class")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material:material-ripple")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")

    // Навигация (батч 15): пер-таб бэкстек + сохранение состояния + транзишены.
    // 2.8.x совместима с Compose 1.7.x из BOM 2024.12.01.
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("io.github.kyant0:backdrop:2.0.0-alpha03")
    implementation("io.github.kyant0:shapes:1.2.0")
    implementation("io.github.kyant0:capsule:2.1.3")
    implementation("io.github.kyant0:fishnet:1.1.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-extractor:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common-ktx:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("androidx.mediarouter:mediarouter:1.7.0")

    implementation("com.google.ai.edge.litert:litert:1.0.1")

    implementation("com.github.wendykierp:JTransforms:3.2")
    implementation("com.mpatric:mp3agic:0.9.1")
    // Редактирование тегов (mp3/flac/m4a/ogg) — Android-патченый форк JAudiotagger.
    implementation("com.github.Adonai:jaudiotagger:2.3.15")

    // Kotlinx Serialization (ICM Partner API + Internal API)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Retrofit (Playlist Import)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Jsoup (HTML parsing for ICM search fallback)
    implementation("org.jsoup:jsoup:1.17.2")

    // WorkManager (background playlist import)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp (ICM Partner API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Chrome Custom Tabs (Telegram Login Widget requires real browser)
    implementation("androidx.browser:browser:1.8.0")

    // Ktor (Internal API client)
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
    implementation("io.ktor:ktor-client-encoding:3.0.0")

    // DataStore (camp preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // EncryptedSharedPreferences — OAuth-токен Яндекса (не plaintext)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room (local database)
    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Requery SQLite (custom native SQLite3) — через JitPack
    implementation("com.github.requery:sqlite-android:3.45.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.12")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}