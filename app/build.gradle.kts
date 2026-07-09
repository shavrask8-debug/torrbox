// МОДУЛЬНИЙ build.gradle.kts (D:\Torrentstreamer\app\build.gradle.kts)
plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.torrentstreamer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.torrentstreamer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Обмежуємо APK лише однією мобільною архітектурою для стабільного запуску на телефонах
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

        // КРИТИЧНО: Вмикаємо системну сумісність бібліотек Java 8+ (Desugaring)
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    // КРИТИЧНО: Додаємо офіційний двигун зворотного проектування байт-коду від Google
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.material3:material3:1.5.0-alpha04")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation("androidx.compose.animation:animation:1.7.5")

    // Форми кутів Squircle
    implementation("androidx.graphics:graphics-shapes:1.0.1")

    // Медіа-сервіс та ExoPlayer
    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // Всеїдний FFmpeg декодер під версію Media3 1.3.1 (від Jellyfin)
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2")

    // Room DB
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Мережа
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Зображення та Життєвий цикл
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // Тести
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}