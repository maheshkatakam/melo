plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing comes from environment variables (set by the GitHub build from repo secrets).
// Without them the release build falls back to the debug key so local builds still work.
val releaseKeystore: String? = System.getenv("MELO_KEYSTORE_PATH")

android {
    namespace = "com.maheshkatakam.melo"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.maheshkatakam.melo"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("MELO_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("MELO_VERSION_NAME") ?: "1.0-dev"

        ndk {
            // Modern 64-bit phones only; keeps the native AI build fast and the APK smaller.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MELO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MELO_KEY_ALIAS")
                keyPassword = System.getenv("MELO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // The speech model is read straight out of the APK, so keep it uncompressed.
        noCompress += listOf("bin")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.coil.compose)

    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.coroutines.play.services)
}
