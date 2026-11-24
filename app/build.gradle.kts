plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vonmi.photopro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vonmi.photopro"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "2.4"
    }
    // Signature fixe : la clé vient des secrets GitHub (jamais stockée dans le dépôt)
    val ksFile = file("release.jks")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    val fixed = ksFile.exists() && !ksPass.isNullOrEmpty()
    signingConfigs {
        if (fixed) create("fixe") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = "photopro"
            keyPassword = ksPass
        }
    }
    buildTypes {
        debug { if (fixed) signingConfig = signingConfigs.getByName("fixe") }
        release { isMinifyEnabled = false; if (fixed) signingConfig = signingConfigs.getByName("fixe") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
