import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
}

// Size switches. The default build is unchanged (both ABIs, embedding model bundled).
//   -PnoEmbeddingModel  leave the ~570 MB embedding model out of the APK; the app then runs
//                       keyword-only until a model is installed (AppEmbeddingEngine.isAvailable).
//   -PabiOnly=<abi>     package one ABI only: arm64-v8a (phones) or x86_64 (emulator).
//   -PphoneApk          no model + arm64-v8a only — the APK to hand to someone with a real
//                       phone. Measured 2026-10-04: 775 MB → 126 MB.
val phoneApk: Boolean = providers.gradleProperty("phoneApk").isPresent
val noEmbeddingModel: Boolean = phoneApk || providers.gradleProperty("noEmbeddingModel").isPresent
val abiOnly: String? = if (phoneApk) "arm64-v8a" else providers.gradleProperty("abiOnly").orNull

android {
    namespace = "com.amar.vault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amar.vault"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // -march removed — it's ARM-only and broke x86_64 builds. If you
                // want ARM SIMD optimization back, add it conditionally inside
                // CMakeLists.txt with an `if(ANDROID_ABI STREQUAL "arm64-v8a")`
                // block instead of here.
                cppFlags += "-std=c++17 -O3"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        ndk {
            abiFilters += abiOnly?.let { listOf(it) } ?: listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("onnx", "tflite", "bin", "litertlm", "task", "gguf")
        if (noEmbeddingModel) {
            // AAPT's default ignore list plus the embedding model.
            ignoreAssetsPattern =
                "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!bge-m3-ocr-int4.onnx"
        }
    }

    lint {
        // Kotlin 2.3.0 vs the bundled lint analysis API crashes ModifierDeclarationDetector
        // (NoClassDefFoundError: KtAnalysisSessionProvider). These Compose-modifier checks are
        // advisory; disabling them (and not aborting on a lint crash) keeps builds green until
        // the AGP/lint version catches up to Kotlin 2.3.0.
        disable += setOf(
            "ModifierFactoryExtensionFunction",
            "ModifierFactoryReturnType",
            "ModifierFactoryUnreferencedReceiver",
        )
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }
}

// Kotlin 2.0+ Compiler Options
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room Schema Export
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    // =========================================================================
    // COMPOSE & UI
    // =========================================================================

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.collection:collection-ktx:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.paging:paging-runtime-ktx:3.3.0")
    implementation("androidx.paging:paging-compose:3.3.0")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("androidx.glance:glance-appwidget:1.0.0")
    implementation("androidx.glance:glance-material3:1.0.0")

    // =========================================================================
    // HILT DI — 2.54 specifically resolves the KspTaskJvm crash in KSP2
    // =========================================================================

    implementation("com.google.dagger:hilt-android:2.57")
    ksp("com.google.dagger:hilt-compiler:2.57")
    ksp("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    // Hilt integration for WorkManager (@HiltWorker + HiltWorkerFactory)
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // =========================================================================
    // ROOM DATABASE — 2.7.0 natively supports KSP2
    // =========================================================================

    val room = "2.7.0"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // =========================================================================
    // KOTLIN & COROUTINES
    // =========================================================================

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")

    // =========================================================================
    // CORE / PLATFORM
    // =========================================================================

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // =========================================================================
    // ML & INFERENCE
    // =========================================================================

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")

    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation("com.google.ai.edge.litert:litert-support:1.0.1")

    // Locked version to 0.10.2. "latest.release" can cause unexpected breaks if they
    // bump metadata requirements again.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // =========================================================================
    // CONNECTIVITY / P2P
    // =========================================================================

    implementation("com.google.android.gms:play-services-nearby:19.1.0")

    // =========================================================================
    // DOCUMENTS
    // =========================================================================

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi:5.2.3")
    implementation("org.apache.poi:poi-ooxml:5.2.3")

    // =========================================================================
    // UNIT TESTS
    // =========================================================================

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("androidx.room:room-testing:$room")

    // =========================================================================
    // INSTRUMENTED TESTS
    // =========================================================================

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // =========================================================================
    // DEBUG TOOLING
    // =========================================================================

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
