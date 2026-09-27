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

// Egress reads a socket's native handle to pin it to the LAN interface on Windows.
val nioExports = listOf("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")
tasks.test { jvmArgs(nioExports) }

tasks.processResources { inputs.property("version", appVersion); filesMatching("version.txt") { expand("version" to appVersion) } }

compose.desktop.application {
    mainClass = "app.fuckpo0jixian.desktop.MainKt"
    jvmArgs += nioExports
    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
        packageName = "FuckPo0JiXian"
        packageVersion = installerVersion
        // Installer metadata stays ASCII: WiX builds the MSI with code page 1252 and rejects Chinese text.
        description = "Keeps the Po0 IPv4 whitelist up to date"
        vendor = "FuckPo0JiXian"
        modules("java.instrument", "jdk.unsupported", "jdk.crypto.ec", "java.naming")
        macOS {
            bundleID = "app.fuckpo0jixian.desktop"
            dockName = "去他妈的鸡险"
            iconFile.set(project.file("icons/icon.icns"))
        }
        windows {
            iconFile.set(project.file("icons/icon.ico"))
            menuGroup = "FuckPo0JiXian"
            shortcut = true
            menu = true
            perUserInstall = true
            // Fixed so new installers upgrade the old one in place.
            upgradeUuid = "6b0f0f2e-3f55-4d4e-9d0a-2b3f3f7e8a51"
        }
    }
}
