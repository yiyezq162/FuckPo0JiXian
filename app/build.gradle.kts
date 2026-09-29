plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose") }
android {
    namespace = "app.fuckpo0jixian"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.fuckpo0jixian"
        minSdk = 28
        targetSdk = 36
        versionCode = 24
        versionName = "0.8.9-preview"
        testInstrumentationRunner = "app.fuckpo0jixian.SafeTestRunner"
    }
    buildFeatures { compose = true }
    signingConfigs {
        // CI passes the published development key explicitly, for the preview build only: a debuggable build signed
        // with it could be installed over the real app and expose its data. Local builds keep ~/.android/debug.keystore.
        System.getenv("FUCKPO0JIXIAN_KEYSTORE")?.let { path -> create("published") {
            storeFile = file(path); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        } }
    }
    buildTypes {
        create("preview") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Test prerelease: same development certificate permits in-place upgrades.
            signingConfig = signingConfigs.findByName("published") ?: signingConfigs.getByName("debug")
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
