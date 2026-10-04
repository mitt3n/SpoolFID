plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing comes from the environment (the release workflow sets these), never from the repo.
 * Absent on a normal dev machine, which simply leaves the release build unsigned.
 */
val signingEnv = listOf(
    "ANDROID_KEYSTORE_FILE",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
).associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }

val hasReleaseSigning = signingEnv.values.all { it != null }

android {
    namespace = "com.spoolfid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.spoolfid"
        minSdk = 26
        targetSdk = 36
        // The release workflow derives both from the git tag (v1.2.3 -> "1.2.3" / 10203).
        versionCode = (findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 10100
        versionName = (findProperty("versionNameOverride") as String?)?.takeIf { it.isNotBlank() } ?: "1.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingEnv.getValue("ANDROID_KEYSTORE_FILE")!!)
                storePassword = signingEnv.getValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingEnv.getValue("ANDROID_KEY_ALIAS")
                keyPassword = signingEnv.getValue("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off: there is no crash reporting, so a release-only breakage would go unnoticed.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.android.json)
}
