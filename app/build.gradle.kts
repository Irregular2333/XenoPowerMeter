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
    // 37.2（次版本 API）：miuix 源码构建以 37.2 编译，AAR 元数据要求宿主 ≥ 37.2
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.irregular.xenopowermeter"
        // miuix-blur（玻璃模糊）要求 minSdk 33（Android 13+），升级前为 24
        minSdk = 33
        targetSdk = 36
        versionCode = 2002002
        versionName = "2.2.2"

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

    // Ship only the locales the app actually translates. Every AAR (AppCompat,
    // Compose, Material) bundles 80+ language tables; without this filter they
    // all land in resources.arsc.
    androidResources {
        localeFilters += listOf("en", "ja", "zh")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation(project(":backdrop"))

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // miuix 0.9.5-beta01 起 glass/blur 模块已发布到 Maven Central，
    // 与 main 分支源码（本地曾用 includeBuild 构建）API 一致。
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.5-beta01")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.5-beta01")
    implementation("top.yukonga.miuix.kmp:miuix-glass:0.9.5-beta01")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.5-beta01")

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
