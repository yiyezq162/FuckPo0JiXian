plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose") }
android {
    namespace = "app.allowmate"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.allowmate"
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0-preview"
        testInstrumentationRunner = "app.allowmate.SafeTestRunner"
    }
    buildFeatures { compose = true }
    signingConfigs {
        // CI passes the published development key explicitly; local builds keep using ~/.android/debug.keystore.
        System.getenv("ALLOWMATE_KEYSTORE")?.let { path -> getByName("debug") { storeFile = file(path) } }
    }
    buildTypes {
        create("preview") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Test prerelease: same development certificate permits in-place upgrades.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            matchingFallbacks += "release"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.11.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
