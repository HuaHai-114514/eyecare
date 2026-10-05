import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.java.myapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.java.myapplication"
        minSdk = 24
        targetSdk = 35
        versionCode = 34
        versionName = "2.6.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("local") {
            // 仅用于本地产物交付（可直接安装的 APK）；凭据不存在时不影响其他构建。
            // 优先级：环境变量 EYECARE_KEYSTORE > user.home > /root/.android/debug.keystore
            val candidates = listOfNotNull(
                System.getenv("EYECARE_KEYSTORE")?.let { File(it) },
                System.getProperty("user.home")?.let { File(it, ".android/debug.keystore") },
                File("/root/.android/debug.keystore"),
            )
            val ks = candidates.firstOrNull { it != null && it.exists() }
            if (ks != null) {
                storeFile = ks
                storePassword = System.getenv("EYECARE_STORE_PASS") ?: "android"
                keyAlias = System.getenv("EYECARE_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("EYECARE_KEY_PASS") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val local = signingConfigs.findByName("local")
            if (local != null && local.storeFile?.exists() == true) {
                signingConfig = local
            }
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
