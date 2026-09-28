import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Server URL / app key defaults come from android/local.properties
// (git-ignored). Debug builds can also change them at runtime
// (Login screen -> Demo controls -> Server settings).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun prop(name: String, default: String): String = localProps.getProperty(name) ?: default

android {
    namespace = "com.example.supportrouter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.supportrouter"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 10.0.2.2 = the host computer, as seen from the Android emulator.
        buildConfigField("String", "SUPPORT_API_URL", "\"${prop("SUPPORT_API_URL", "http://10.0.2.2:8000")}\"")
        buildConfigField("String", "SUPPORT_APP_KEY", "\"${prop("SUPPORT_APP_KEY", "dev-key-change-me")}\"")
        // Empty => the app generates an anonymous per-install ID (the login
        // journey is UNAUTHENTICATED, so there is no real user id yet).
        buildConfigField("String", "SUPPORT_USER_ID", "\"${prop("SUPPORT_USER_ID", "")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
