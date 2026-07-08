plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") // Використовуємо високопродуктивний KSP замість застарілого Kapt
    id("org.jetbrains.kotlin.plugin.compose") // Новий плагін для Kotlin 2.x
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
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Фіксуємо чіткі версії Compose для уникнення будь-яких конфліктів з BOM у Gradle
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.material3:material3:1.5.0-alpha04") // Нова Expressive версія
    implementation("androidx.compose.material:material-icons-extended:1.7.5") // Фіксована версія іконок
    implementation("androidx.compose.animation:animation:1.7.5") // ВИПРАВЛЕНО: Додано явну залежність для плавного кольорового анімування

    // Офіційна бібліотека Google для прорахунку Squircle кутів
    implementation("androidx.graphics:graphics-shapes:1.0.1")

    // Медіа-сервіс та ExoPlayer
    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // Локальна база даних (Кеш та історія переглядів)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1") // Високопродуктивний ksp компілятор Room

    // Мережа
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Завантаження зображень
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
}