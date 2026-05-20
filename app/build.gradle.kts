plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.3.10"
}

android {
    namespace = "com.liquidmusicglass"

    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.liquidmusicglass"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Read ICM API key from local.properties (GitHub Secret in CI)
        // Fallback to native .so (JNI) at runtime for maximum protection
        val icmApiKey = run {
            val f = rootProject.file("local.properties")
            if (!f.exists()) return@run ""
            val lines = f.readLines()
            val prefix = "ICM_API_KEY="
            lines.find { it.startsWith(prefix) }?.removePrefix(prefix)?.trim() ?: ""
        }
        buildConfigField("String", "ICM_API_KEY", "\"$icmApiKey\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release-key.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "St@skrasikov1"
            keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "St@skrasikov1"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            // Debug uses auto-generated debug signing
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName("release")
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

    ndkVersion = "27.2.12479018"

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

    implementation("io.github.kyant0:backdrop:2.0.0-alpha03")
    implementation("io.github.kyant0:shapes:1.2.0")
    implementation("io.github.kyant0:capsule:2.1.3")
    implementation("io.github.kyant0:fishnet:1.1.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common-ktx:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("androidx.mediarouter:mediarouter:1.7.0")

    implementation("com.google.ai.edge.litert:litert:1.0.1")

    implementation("com.github.wendykierp:JTransforms:3.2")

    // Kotlinx Serialization (ICM Partner API + Internal API)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

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

    // Room (local database)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

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