package app.fuckpo0jixian

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * In-app update: finds the APK in the newest release, downloads and verifies it (SHA-256), then hands it to
 * Android's package installer, which shows its own confirmation and only accepts an APK signed with this app's
 * certificate. The Magisk / KernelSU module is not touched; it is updated in the manager as before.
 */
class AppUpdater(private val context: Context) {
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val version: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    private val client = UpdateClient("FuckPo0JiXian-Android/$version")
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** The release the update dialog is offering; null when no dialog is shown. */
    val offer = MutableStateFlow<Update?>(null)

    /** [propose]: offer what the check finds in the update dialog, unless that version was skipped. */
    fun check(manual: Boolean, interval: Long = Updates.AUTO_INTERVAL_MS, propose: Boolean = false) {
        if (job?.isActive == true) return
        if (!manual && System.currentTimeMillis() - prefs.getLong("checked", 0) < interval) {
            if (propose) propose((state.value as? UpdateState.Available)?.update)
            return
        }
        job = scope.launch {
            val before = state.value
            if (manual) state.value = UpdateState.Checking
            try {
                val update = client.check(version, Updates::androidAsset)
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                state.value = if (update != null) UpdateState.Available(update) else UpdateState.Latest(System.currentTimeMillis())
                if (propose) propose(update)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = if (manual) UpdateState.Failed(updateText((e as? ApiFailure)?.code ?: "UPDATE_OFFLINE")) else before }
        }
    }

    /** The app came back to the foreground: look for a release and offer it, at most every few minutes. */
    fun resumed() = check(manual = false, interval = Updates.RESUME_INTERVAL_MS, propose = true)

    /** 跳过此版本: the dialog never offers this version again; the settings page still can install it. */
    fun skip(update: Update) {
        prefs.edit().putString("skipped", update.version).apply()
        offer.value = null
    }
    fun closeOffer() { offer.value = null }
    private fun propose(update: Update?) {
        if (Updates.offer(update, prefs.getString("skipped", null))) offer.value = update
    }

    fun install(update: Update) {
        if (job?.isActive == true) return
        // Android asks once whether this app may install apps; send people straight to that switch.
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            state.value = UpdateState.Failed("允许「安装未知应用」后再点更新", update)
            return
        }
        job = scope.launch {
            try {
                state.value = UpdateState.Downloading(update, null)
                val apk = client.download(update, File(context.cacheDir, "updates"), { isActive }) { done, total ->
                    state.value = UpdateState.Downloading(update, if (total > 0) done.toFloat() / total else null)
                }
                state.value = UpdateState.Installing(update)
                commit(apk)
            } catch (e: CancellationException) { state.value = UpdateState.Available(update); throw e }
            catch (e: Exception) { state.value = UpdateState.Failed(updateText((e as? ApiFailure)?.code ?: "UPDATE_OFFLINE"), update) }
        }
    }

    fun cancel() { job?.cancel() }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val status = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(status.intentSender)
        }
    }

    /** Called by [UpdateReceiver] with the installer's verdict. */
    internal fun result(status: Int, message: String?) {
        val update = (state.value as? UpdateState.Installing)?.update
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION)
            state.value = UpdateState.Failed(if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "已取消安装"
                else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) "签名或版本不匹配，无法覆盖安装"
                else "安装未完成（${message?.take(40) ?: status}）", update)
    }
}

/** Package installer callbacks: show the system confirmation, report the outcome. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        (context.applicationContext as FuckPo0JiXianApp).controller.updater.result(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
