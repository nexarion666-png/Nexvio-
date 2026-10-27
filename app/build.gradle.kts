plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }

android { namespace = "com.nexvio.app"; compileSdk = 35
    defaultConfig {
        applicationId = "com.nexvio.app"; minSdk = 24; targetSdk = 35
        // Supabase URL and publishable key are client-safe configuration, not server secrets.
        buildConfigField("String", "SUPABASE_URL", "\"https://lbgssprmmqikzmyifphi.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_KOjZfEB_Xx7l5lmHHJlIYg_K1SrDBn-\"")
        versionCode = (System.getenv("NEXVIO_VERSION_CODE") ?: "7").toInt()
        versionName = System.getenv("NEXVIO_VERSION_NAME") ?: "1.0.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            val ks = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!ks.isNullOrBlank()) storeFile = file(ks)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            // When CI provides the permanent keystore, sign distributable debug APKs
            // with it so they can update the existing installation.
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            // Use the release keystore only when the configured file actually exists.
            // Otherwise keep Android's standard debug signing so CI can build debug APKs.
            if (!keystorePath.isNullOrBlank() && file(keystorePath).isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("release") { signingConfig = signingConfigs.getByName("release") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json methods are stubs in local JVM unit tests. Supply a real
    // implementation so manifest parsers can be tested without an emulator.
    testImplementation("org.json:json:20240303")
}
