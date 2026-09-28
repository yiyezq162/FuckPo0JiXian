package app.fuckpo0jixian

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

sealed interface ModuleInstallState {
    data object Idle : ModuleInstallState
    data class Downloading(val fraction: Float?) : ModuleInstallState
    /** The root manager opened the zip itself; it asks to install and reboot. */
    data class Opened(val manager: String) : ModuleInstallState
    /** Saved for manual install; [manager] was opened when one was found. */
    data class Saved(val manager: String?, val location: String, val file: String) : ModuleInstallState
    data class Failed(val message: String) : ModuleInstallState
}

/**
 * Fetches the Magisk / KernelSU module released with this APK (the helper only trusts an APK at least as new as
 * itself, so it is always the same version), verifies it like an APK update (SHA-256 from the release), keeps a copy
 * in Downloads and hands it to the root manager. The APK itself never asks for root: installing stays a step people
 * confirm in their manager.
 */
class ModuleInstaller(private val context: Context) {
    val state = MutableStateFlow<ModuleInstallState>(ModuleInstallState.Idle)
    private val version: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    private val client = UpdateClient("FuckPo0JiXian-Android/$version")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Root managers, most specific first. A Magisk app hidden under a random package name is not found. */
    private val managers = listOf(
        "me.weishu.kernelsu" to "KernelSU", "com.rifsxd.ksunext" to "KernelSU Next", "com.sukisu.ultra" to "SukiSU",
        "me.bmax.apatch" to "APatch", "com.topjohnwu.magisk" to "Magisk", "io.github.huskydg.magisk" to "Kitsune Mask")

    fun reset() { if (job?.isActive != true) state.value = ModuleInstallState.Idle }
    fun cancel() { job?.cancel() }

    fun install() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                state.value = ModuleInstallState.Downloading(null)
                LifeLog.add("MODULE_INSTALL download $version")
                val update = client.find(version, Updates::runtimeAsset) ?: throw ApiFailure("MODULE_NOT_RELEASED")
                val zip = client.download(update, File(context.cacheDir, "module"), { isActive }) { done, total ->
                    state.value = ModuleInstallState.Downloading(if (total > 0) done.toFloat() / total else null)
                }
                val location = runCatching { keepCopy(zip) }.getOrNull()
                val outcome = withContext(Dispatchers.Main) { hand(zip, location) }
                LifeLog.add("MODULE_INSTALL ${outcome.javaClass.simpleName} ${(outcome as? ModuleInstallState.Opened)?.manager
                    ?: (outcome as? ModuleInstallState.Saved)?.manager ?: ""}")
                state.value = outcome
            } catch (e: CancellationException) { state.value = ModuleInstallState.Idle; throw e }
            catch (e: Exception) {
                val code = (e as? ApiFailure)?.code ?: "UPDATE_OFFLINE"
                LifeLog.add("MODULE_INSTALL failed $code")
                state.value = ModuleInstallState.Failed(if (code == "MODULE_NOT_RELEASED") "没有找到与 $version 对应的模块" else updateText(code))
            }
        }
    }

    /** A copy people can pick from their manager's "install from storage"; the previous copy of the same name is replaced. */
    private fun keepCopy(zip: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            runCatching { resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(zip.name)) }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, zip.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(collection, values))
            resolver.openOutputStream(uri).use { out -> zip.inputStream().use { it.copyTo(requireNotNull(out)) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return "下载"
        }
        val dir = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
        zip.copyTo(File(dir, zip.name), overwrite = true)
        return "Android/data/${context.packageName}/files/Download"
    }

    /** Opens the zip in a root manager that accepts it (KernelSU and its forks do), else just opens the manager. */
    private fun hand(zip: File, location: String?): ModuleInstallState {
        val pm = context.packageManager
        val installed = managers.filter { (pkg, _) -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        for ((pkg, name) in installed) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/zip").setPackage(pkg)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pm.resolveActivity(view, 0) != null && runCatching { context.startActivity(view) }.isSuccess)
                return ModuleInstallState.Opened(name)
        }
        val launched = installed.firstOrNull { (pkg, _) ->
            pm.getLaunchIntentForPackage(pkg)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess } == true
        }?.second
        return ModuleInstallState.Saved(launched, location ?: "应用缓存", zip.name)
    }
}
