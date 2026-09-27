import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins { kotlin("jvm"); kotlin("plugin.compose"); id("org.jetbrains.compose") }
kotlin { jvmToolchain(17) }

/** Shown in the app; follows the Android versionName, e.g. 0.7.0-preview. */
val appVersion: String = Regex("versionName = \"([^\"]+)\"").find(rootProject.file("app/build.gradle.kts").readText())!!.groupValues[1]
/** Installers require MAJOR > 0 and digits only, so 0.7.0-preview is packaged as 1.7.0 (metadata only). */
val installerVersion: String = Regex("^0\\.(\\d+)\\.(\\d+)").find(appVersion)!!.destructured.let { (minor, patch) -> "1.$minor.$patch" }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation(kotlin("test-junit"))
}

tasks.processResources { inputs.property("version", appVersion); filesMatching("version.txt") { expand("version" to appVersion) } }

compose.desktop.application {
    mainClass = "app.fuckpo0jixian.desktop.MainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
        packageName = "FuckPo0JiXian"
        packageVersion = installerVersion
        description = "自动维护 Po0 的 IPv4 白名单"
        vendor = "FuckPo0JiXian"
        modules("java.instrument", "jdk.unsupported", "jdk.crypto.ec", "java.naming")
        macOS {
            bundleID = "app.fuckpo0jixian.desktop"
            dockName = "去他妈的鸡险"
            iconFile.set(project.file("icons/icon.icns"))
        }
        windows {
            iconFile.set(project.file("icons/icon.ico"))
            menuGroup = "去他妈的鸡险"
            shortcut = true
            menu = true
            perUserInstall = true
            // Fixed so new installers upgrade the old one in place.
            upgradeUuid = "6b0f0f2e-3f55-4d4e-9d0a-2b3f3f7e8a51"
        }
    }
}
