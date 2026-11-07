plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fares.iffalconremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fares.iffalconremote"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
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
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")

    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
}
