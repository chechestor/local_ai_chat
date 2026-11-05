plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// -Plocalaichat.skipNative=true — сборка без llama.cpp (на ПК без NDK: проверка Kotlin, тесты, lint).
// APK без нативной части модель не загрузит; в CI флаг не используется.
val skipNative = providers.gradleProperty("localaichat.skipNative").map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "com.chechestor.localaichat"
    compileSdk = 35
    // NDK и CMake зафиксированы под предустановленные на раннере ubuntu-latest (лицензии там приняты), см. decisions.md.
    if (!skipNative) ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.chechestor.localaichat"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Целевое устройство — ARM64. x86_64 не собираем: удвоило бы время сборки llama.cpp в CI.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        if (!skipNative) externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        debug {
            // Стандартный debug-ключ Android, своих ключей и секретов нет.
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (!skipNative) externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
        }
    }

    packaging {
        // Библиотеки распаковываются на диск: ggml ищет варианты CPU-бэкенда (libggml-cpu-*.so) в nativeLibraryDir.
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.androidx.datastore.preferences)
    // Vosk (F005): JNA подключается в варианте AAR — в нём нативная libjnidispatch.so для Android.
    implementation(libs.vosk.android) { exclude(group = "net.java.dev.jna") }
    implementation(libs.jna) { artifact { type = "aar" } }
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
