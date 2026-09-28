package app.fuckpo0jixian

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LocalStore(context: Context) : StateStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "state-v1.json"))
    private val legacy = AtomicFile(File(context.noBackupFilesDir, "state-v1.recovery.json"))
    private var recoveryRequired = false
    val flow = MutableStateFlow(read())
    private fun read(): State = try {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) State() else {
            val raw = file.openRead().bufferedReader().use { it.readText() }
            val decoded = StateCodec.decode(raw)
            val now = System.currentTimeMillis()
            val cutoff = now - Policy().retentionMs
            val pruned = decoded.loaded(now).copy(observations = decoded.observations.filter { it.time >= cutoff }.takeLast(500),
                events = decoded.events.filter { it.time >= cutoff }.takeLast(200),
                domesticExit = decoded.domesticExit?.takeIf { it.time >= cutoff })
            if (raw != StateCodec.encode(pruned)) {
                if (!legacy.baseFile.exists()) {
                    val out = legacy.startWrite()
                    try { out.write(raw.toByteArray()); legacy.finishWrite(out) }
                    catch (e: Exception) { legacy.failWrite(out); throw e }
                }
                persist(pruned)
            }
            pruned
        }
    } catch (_: Exception) { recoveryRequired = true; State(status = "STORAGE_RECOVERY_REQUIRED", globalBlock = "STORAGE_RECOVERY_REQUIRED") }
    // StateFlow publishes an immutable snapshot after the atomic write finishes.
    // Readers must not wait on fsync while an IO worker owns save's monitor.
    override fun load() = flow.value
    @Synchronized override fun save(state: State) {
        check(!recoveryRequired) { "STORAGE_RECOVERY_REQUIRED" }
        persist(state)
        flow.value = state
    }
    private fun persist(state: State) {
        val output = file.startWrite()
        try { output.write(StateCodec.encode(state).toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
}

class TokenVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "credential.enc"))
    private val alias = "fuckpo0jixian-token-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun exists() = file.baseFile.exists()
    @Synchronized fun read(): String? {
        if (!exists()) return null
        val lines = file.openRead().bufferedReader().use { it.readLines() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(lines[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(lines[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    @Synchronized fun save(token: String) {
        require(token.matches(Regex("pgnfw_[A-Za-z0-9_-]+")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray())
        val bytes = (Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "\n" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).toByteArray()
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    @Synchronized fun clear() { file.delete() }
}
