import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Reads RAPIDAPI_KEY from local.properties (git-ignored) so the real key
// never ends up committed to source control. Create local.properties in
// the project root with a line like:
//   RAPIDAPI_KEY=your_key_here
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.geto.jido"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.geto.jido"
        minSdk = 26          // Oreo — required for NotificationChannel APIs used directly
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "RAPIDAPI_KEY",
            "\"${localProperties.getProperty("RAPIDAPI_KEY", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")

    // Coroutines for off-main-thread work
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // HTTP client for the RapidAPI downloader calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // org.json is bundled with the Android SDK — no extra dependency needed
    // for parsing the RapidAPI JSON response.

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
