plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "pl.beepoo.ztmwidget"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.beepoo.ztmwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.6-test"
    }
    // Stały klucz (w repo), żeby każde APK z GitHub Actions instalowało się jako aktualizacja poprzedniego.
    signingConfigs {
        create("ci") {
            storeFile = file("ztm-ci.p12")
            storeType = "pkcs12"
            storePassword = "ztmwidget"
            keyAlias = "ztm"
            keyPassword = "ztmwidget"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("ci") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ci")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
