plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "studio.kodrika.yaziobridge"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "studio.kodrika.yaziobridge"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            optimization { enable = true }
            proguardFiles("proguard-rules.pro")
            // Kişisel kullanım: ayrı imza anahtarı yok, debug anahtarıyla imzalanır (Play'e çıkmayacak)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.health.connect)
}
