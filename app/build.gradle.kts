import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from a file OUTSIDE the repository so the keystore and passwords are never committed.
// Default: ~/.arislushai/keystore.properties (storeFile, storePassword, keyAlias, keyPassword); override with
// the ARISLUSHAI_SIGNING environment variable. Without it, `assembleRelease` produces an unsigned APK.
val signingProps = Properties().apply {
    val path = System.getenv("ARISLUSHAI_SIGNING") ?: "${System.getProperty("user.home")}/.arislushai/keystore.properties"
    val f = file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.arislushai"
    // Current AndroidX libraries require compileSdk 37; runtime target stays 36 (the Galaxy A54 runs Android 16).
    compileSdk = 37

    defaultConfig {
        applicationId = "app.arislushai"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Phones are arm64-v8a; keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 is off on purpose: sherpa-onnx classes are looked up from native code (JNI) and would need keep rules
            // that have not been tested yet.
            isMinifyEnabled = false
            if (signingProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    // sherpa-onnx v2.0.1 AAR: not on Maven Central and not committed. Run scripts/fetch-deps.ps1 (checks SHA-256).
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.translate)

    testImplementation("junit:junit:4.13.2")
}
