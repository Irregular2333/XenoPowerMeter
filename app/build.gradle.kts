import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

android {
    namespace = "com.irregular.xenopowermeter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.irregular.xenopowermeter"
        minSdk = 24
        targetSdk = 34
        versionCode = 2000000
        versionName = "2.0.0"

        // Bake the build date into the APK at compile time (zip entry
        // timestamps are normalized by the toolchain, so the About page
        // reads this instead). Day granularity: same-day rebuilds don't
        // invalidate incremental compilation.
        val buildDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":backdrop"))

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Shapes (required by backdrop)

    // Activity & ViewModel
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Navigation

    // USB Serial
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Core

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // AppCompat (required for locale switching)
    implementation("androidx.appcompat:appcompat:1.7.0")
}
