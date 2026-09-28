package app.fuckpo0jixian

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.AtomicFile
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** What the helper reported about itself at the last handshake (INFO); null fields come from older modules. */
data class ModuleInfo(val version: Long?, val versionName: String?, val network: String?, val gateway: String?, val wan: String?) {
    /** Older than this APK: the module release matching this APK has fixes or features it lacks. */
    fun outdated(apkVersion: Long) = version == null || version < apkVersion
}

/** No su requests, credentials or arbitrary commands on this channel; the helper only shares what it observed. */
class RuntimeBridge(private val context: Context, private val bootCount: Int = runCatching {
    android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
}.getOrDefault(-1)) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "runtime-v1.json"))
    private val receipt = AtomicFile(File(context.noBackupFilesDir, "runtime-result-v1.json"))
    private val proof = AtomicFile(File(context.noBackupFilesDir, "runtime-proof-v1"))
    private val observation = AtomicFile(File(context.noBackupFilesDir, "runtime-observation-v1.json"))
    private val publication = Mutex()
    private val evidenceLock = Mutex()
    private val instance = runCatching { JSONObject(file.openRead().bufferedReader().use { it.readText() }).getString("instance") }
        .getOrElse { UUID.randomUUID().toString() }
    val status = MutableStateFlow("尚未检查模块 · 标准调度兜底")
    val checkedAt = MutableStateFlow(0L)
    private fun history(source: AtomicFile, empty: String): String = runCatching {
        val raw = source.openRead().bufferedReader().use { it.readText() }
        val data = runCatching { JSONObject(raw) }.getOrNull()
        if (data == null) "历史记录（开机归属未知，不作当前依据）：$raw"
        else (if (bootCount >= 0 && data.optInt("boot", -1) == bootCount) "本次开机最近记录" else "历史记录（非本次开机或归属未知）") +
            " · " + data.getString("text")
    }.getOrDefault(empty)
    val result = MutableStateFlow(history(receipt, "尚无增强请求完成证据"))
    /** Latest helper self-report; null when no helper answered. */
    val module = MutableStateFlow<ModuleInfo?>(null)
    val apkVersion: Long = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0)
    private val eventPrefs = context.getSharedPreferences("module_events", Context.MODE_PRIVATE)
    val lastConnection = MutableStateFlow(history(observation, "本次开机尚无已验证的 helper 报告"))
    private fun writeEvidence(target: AtomicFile, data: JSONObject) {
        val out = target.startWrite()
        try { out.write(data.toString().toByteArray()); target.finishWrite(out) }
        catch (e: Exception) { target.failWrite(out); throw e }
    }
    suspend fun publish(s: State) = withContext(Dispatchers.IO) { publication.withLock {
        val previous = runCatching { JSONObject(file.openRead().bufferedReader().use { it.readText() }) }.getOrNull()
        val gate = "${s.runtimeMode}:${s.paused}:${RuntimePolicy.enabled(s)}"
        val generation = if (previous?.optString("gate") == gate) previous.optString("generation") else UUID.randomUUID().toString()
        if (!RuntimePolicy.enabled(s)) proof.delete()
        val bytes = JSONObject().put("protocol", RuntimePolicy.PROTOCOL).put("instance", instance)
            .put("gate", gate).put("generation", generation)
            .put("enabled", RuntimePolicy.enabled(s)).put("fallbackMinutes", FallbackInterval.clamp(s.fallbackMinutes))
            // Module mode asks the root helper to keep this app exempt from battery optimization (see module README).
            .put("keepAlive", true)
            .toString().toByteArray()
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        refresh(s)
    } }
    suspend fun exchange(action: String, ticket: String = "", limit: Int = 2048): String? = withContext(Dispatchers.IO) {
        runCatching {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress("fuckpo0jixian.runtime.v1", LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 2000
                check(socket.peerCredentials.uid == 0) // A different app cannot impersonate the helper.
                val output = DataOutputStream(socket.outputStream)
                output.writeUTF("1"); output.writeUTF(action); output.writeUTF(instance); output.writeUTF(ticket); output.flush()
                DataInputStream(socket.inputStream).readUTF().take(limit)
            }
        }.onFailure { android.util.Log.w("FuckPo0JiXianRuntime", "IPC_" + it.javaClass.simpleName) }.getOrNull()
    }
    suspend fun refresh(s: State) = withContext(Dispatchers.IO) { evidenceLock.withLock {
        val info = exchange("INFO")?.let { runCatching { JSONObject(it) }.getOrNull() }
        module.value = info?.let {
            ModuleInfo(it.optLong("version", -1).takeIf { v -> v >= 0 }, it.optString("versionName").ifBlank { null },
                it.optString("network").ifBlank { null }, it.optString("gateway").ifBlank { null },
                it.optString("wan").takeIf { w -> w.isNotBlank() && w != "null" })
        }
        if (info != null) pullEvents(info)
        val sameBoot = info != null && bootCount >= 0 && info.optInt("boot", -1) == bootCount
        val reply = if (info == null) exchange("STATUS") else if (!sameBoot) "BOOT_UNKNOWN" else info.optString("status")
        val seenThisBoot = bootCount >= 0 && runCatching {
            JSONObject(observation.openRead().bufferedReader().use { it.readText() }).optInt("boot", -1) == bootCount
        }.getOrDefault(false)
        val state = if (reply == null && !seenThisBoot) "NOT_SEEN" else if (reply?.matches(Regex("READY:[a-f0-9-]{36}")) == true) {
            if (sameBoot && validated(reply.removePrefix("READY:"))) "ACTIVE" else "READY"
        } else reply
        if (sameBoot) {
            val text = "${System.currentTimeMillis()} · root 身份握手 · 开机 $bootCount · ${info!!.getString("status")} · helper 启动于开机后 ${info.optLong("startedMs", -1)}ms（仅当时状态）"
            writeEvidence(observation, JSONObject().put("boot", bootCount).put("text", text))
            lastConnection.value = history(observation, "报告不可用")
        }
        val idle = context.getSystemService(android.os.PowerManager::class.java).isDeviceIdleMode
        status.value = RuntimePolicy.status(s.runtimeMode, s.paused,
            if (idle && state in setOf("READY", "ACTIVE")) "DOZE" else state)
        checkedAt.value = System.currentTimeMillis()
    } }
    /**
     * Copies the helper's in-memory timeline into the lifecycle log (addresses cut to /16 there). The helper numbers
     * its lines from 1 each time it starts, so the position is kept per helper start.
     */
    private suspend fun pullEvents(info: JSONObject) {
        val origin = "${info.optInt("boot", -1)}:${info.optLong("startedMs", -1)}"
        val since = if (eventPrefs.getString("origin", null) == origin) eventPrefs.getLong("seq", 0) else 0
        val reply = exchange("EVENTS", since.toString(), limit = 65_535)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val lines = reply.optJSONArray("lines") ?: return
        for (i in 0 until lines.length()) {
            val parts = lines.optString(i).split(' ', limit = 3)
            val at = parts.getOrNull(1)?.toLongOrNull() ?: continue
            LifeLog.add("MODULE ${parts.getOrElse(2) { "" }}", at)
        }
        eventPrefs.edit().putString("origin", origin).putLong("seq", reply.optLong("seq", since)).apply()
    }

    suspend fun claim(ticket: String): String? = exchange("CLAIM", ticket)?.takeIf {
        it.matches(Regex("CHECK:[a-f0-9-]{36}"))
    }?.removePrefix("CHECK:")
    suspend fun validated(epoch: String): Boolean = withContext(Dispatchers.IO) {
        bootCount >= 0 && runCatching { proof.openRead().bufferedReader().use { it.readText() } == "$bootCount:$instance:$epoch" }.getOrDefault(false)
    }
    suspend fun validate(epoch: String) = withContext(Dispatchers.IO) {
        val out = proof.startWrite()
        try { out.write("$bootCount:$instance:$epoch".toByteArray()); proof.finishWrite(out) }
        catch (e: Exception) { proof.failWrite(out); throw e }
    }
    suspend fun record(code: String, elapsed: Long, completed: Boolean, readOnly: Boolean = true) = withContext(Dispatchers.IO) { evidenceLock.withLock {
        val text = "${System.currentTimeMillis()} · UID ${android.os.Process.myUid()} · ${elapsed}ms · $code · " +
            if (completed) (if (readOnly) "APK 只读请求完成" else "APK 同步请求完成") else "未证明请求完成"
        writeEvidence(receipt, JSONObject().put("boot", bootCount).put("text", text))
        result.value = history(receipt, "结果不可用")
    } }
}
