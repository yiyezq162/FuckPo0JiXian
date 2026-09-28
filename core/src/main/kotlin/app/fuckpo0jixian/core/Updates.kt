package app.fuckpo0jixian.core

import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String? = null)
data class Release(val version: String, val notes: String, val page: String, val assets: List<ReleaseAsset>)
/** A newer release that carries the installer this device needs. */
data class Update(val release: Release, val asset: ReleaseAsset) {
    val version get() = release.version
}

/** What an update button shows; the same on every platform. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Latest(val checkedAt: Long) : UpdateState
    data class Available(val update: Update) : UpdateState
    data class Downloading(val update: Update, val fraction: Float?) : UpdateState
    data class Installing(val update: Update) : UpdateState
    data class Failed(val message: String, val update: Update? = null) : UpdateState

    /** For logs: stable words, unlike class names, which R8 renames in release builds. */
    val label: String get() = when (this) {
        Idle -> "idle"; Checking -> "checking"; is Latest -> "latest"; is Available -> "available ${update.version}"
        is Downloading -> "downloading ${update.version}"; is Installing -> "installing ${update.version}"; is Failed -> "failed $message"
    }
}

/**
 * In-app updates from this project's GitHub Releases. Only published releases count (drafts are invisible to
 * the public API anyway); previews are the normal channel. Every download is checked against the SHA-256 GitHub
 * reports for the asset, or the release's SHA256SUMS files, before anything is installed. Android additionally
 * refuses an APK signed with another certificate.
 */
object Updates {
    const val REPO = "yiyezq162/FuckPo0JiXian"
    const val API = "https://api.github.com/repos/$REPO/releases?per_page=10"
    const val PAGE = "https://github.com/$REPO/releases"
    /** Automatic checks at most once a day; the button always checks. */
    const val AUTO_INTERVAL_MS = 24 * 3_600_000L

    fun androidAsset(version: String) = "FuckPo0JiXian-$version.apk"
    /** The Magisk / KernelSU module released together with the APK of the same version. */
    fun runtimeAsset(version: String) = "FuckPo0JiXian-Runtime-$version.zip"
    /** platform: macos / windows; arch: arm64 / x64; ext: dmg / msi. */
    fun desktopAsset(version: String, platform: String, arch: String, ext: String) =
        "FuckPo0JiXian-Desktop-$version-$platform-$arch.$ext"

    fun parse(json: String): List<Release> = Json.parseToJsonElement(json).jsonArray.mapNotNull { e ->
        val o = e.jsonObject
        if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val version = tag.removePrefix("v")
        if (numbers(version) == null) return@mapNotNull null
        Release(version, o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["html_url"]?.jsonPrimitive?.contentOrNull ?: PAGE,
            o["assets"]?.jsonArray.orEmpty().mapNotNull { a ->
                val x = a.jsonObject
                val name = x["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = x["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                // Only this repository's release downloads; never a URL the response points elsewhere.
                if (!url.startsWith("https://github.com/$REPO/releases/download/")) return@mapNotNull null
                ReleaseAsset(name, url, x["size"]?.jsonPrimitive?.longOrNull ?: 0,
                    x["digest"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase())
            })
    }

    /** 0.7.6-preview → [0, 7, 6]. */
    fun numbers(version: String): List<Int>? {
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(version.trim().removePrefix("v")) ?: return null
        return m.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    /** A pre-release carries a suffix after its numbers, e.g. 0.8.3-preview. */
    fun prerelease(version: String) = version.trim().removePrefix("v").replace(Regex("^\\d+\\.\\d+\\.\\d+"), "").isNotEmpty()

    /** Higher numbers win; with equal numbers a final release is newer than its pre-release (0.8.3 > 0.8.3-preview). */
    fun newer(candidate: String, current: String): Boolean {
        val a = numbers(candidate) ?: return false
        val b = numbers(current) ?: return true // "dev" builds take any release
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return prerelease(current) && !prerelease(candidate)
    }

    /** The newest release above [current] that has [assetName]'s installer; null when up to date. */
    fun pick(releases: List<Release>, current: String, assetName: (String) -> String): Update? = releases
        .filter { newer(it.version, current) }
        .sortedWith { x, y -> if (newer(x.version, y.version)) -1 else if (newer(y.version, x.version)) 1 else 0 }
        .firstNotNullOfOrNull { r -> r.assets.find { it.name == assetName(r.version) }?.let { Update(r, it) } }

    /**
     * The release of exactly [version] carrying [assetName]'s file: the module must match the installed APK, so a
     * newer release is no substitute. Null for dev builds and unpublished (draft) releases.
     */
    fun exact(releases: List<Release>, version: String, assetName: (String) -> String): Update? =
        releases.firstOrNull { it.version == version.trim().removePrefix("v") }
            ?.let { r -> r.assets.find { it.name == assetName(r.version) }?.let { Update(r, it) } }

    /** "hash  name" lines as written by shasum / sha256sum. */
    fun checksum(sums: String, name: String): String? = sums.lines().map { it.trim().split(Regex("\\s+\\*?"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1] == name && it[0].matches(Regex("[0-9a-fA-F]{64}")) }?.get(0)?.lowercase()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Release notes as a few short lines, without markdown links or the auto-generated changelog footer. */
    fun summary(notes: String, lines: Int = 6): String = notes.lines().map { it.trim() }
        .takeWhile { !it.startsWith("**Full Changelog**") }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("<!--") }
        .map { it.removePrefix("- ").removePrefix("* ").replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1") }
        .take(lines).joinToString("\n")
}

/**
 * Plain HTTPS through the system's normal routing (proxies and VPNs included); this path has nothing to do with the
 * whitelist checks, which keep their own pinned transports. Blocking calls: run them off the main thread.
 */
class UpdateClient(private val userAgent: String,
                   private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }) {
    private fun connect(url: String, accept: String): HttpURLConnection = open(url).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", userAgent); setRequestProperty("Accept", accept)
    }

    fun check(current: String, assetName: (String) -> String): Update? = Updates.pick(releases(), current, assetName)
    fun find(version: String, assetName: (String) -> String): Update? = Updates.exact(releases(), version, assetName)

    fun releases(): List<Release> {
        val c = connect(Updates.API, "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw ApiFailure("UPDATE_HTTP_${c.responseCode}", c.responseCode)
            return Updates.parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    private fun text(url: String): String {
        val c = connect(url, "application/octet-stream")
        try {
            if (c.responseCode != 200) throw ApiFailure("UPDATE_HTTP_${c.responseCode}", c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText().take(65_536) }
        } finally { c.disconnect() }
    }

    /** SHA-256 the release vouches for: GitHub's asset digest, else the SHA256SUMS files. */
    fun expectedSha(update: Update): String? = update.asset.sha256 ?: update.release.assets
        .filter { it.name.startsWith("SHA256SUMS") && it.size in 1..65_536 }
        .firstNotNullOfOrNull { runCatching { Updates.checksum(text(it.url), update.asset.name) }.getOrNull() }

    /**
     * Downloads into [dir] and verifies it; a file that does not match is deleted. [progress] gets (done, total);
     * returning false from [active] cancels.
     */
    fun download(update: Update, dir: File, active: () -> Boolean = { true }, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val expected = expectedSha(update) ?: throw ApiFailure("UPDATE_NO_CHECKSUM")
        dir.mkdirs()
        dir.listFiles()?.filter { it.name != update.asset.name }?.forEach { it.delete() } // one installer at a time
        val target = File(dir, update.asset.name)
        if (target.exists() && Updates.sha256(target) == expected) return target
        val part = File(dir, update.asset.name + ".part")
        val c = connect(update.asset.url, "application/octet-stream")
        try {
            if (c.responseCode != 200) throw ApiFailure("UPDATE_HTTP_${c.responseCode}", c.responseCode)
            val total = c.contentLengthLong.takeIf { it > 0 } ?: update.asset.size
            c.inputStream.use { input -> part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    if (!active()) throw ApiFailure("UPDATE_CANCELLED")
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n); done += n; progress(done, total)
                }
            } }
        } catch (e: Exception) { part.delete(); throw e } finally { c.disconnect() }
        if (Updates.sha256(part) != expected) { part.delete(); throw ApiFailure("UPDATE_CHECKSUM_MISMATCH") }
        target.delete()
        if (!part.renameTo(target)) throw ApiFailure("UPDATE_SAVE_FAILED")
        return target
    }
}

fun updateText(code: String): String = when (code) {
    "UPDATE_NO_CHECKSUM" -> "无法校验安装包，已取消"
    "UPDATE_CHECKSUM_MISMATCH" -> "安装包校验失败，已删除"
    "UPDATE_CANCELLED" -> "已取消下载"
    "UPDATE_SAVE_FAILED" -> "无法保存安装包"
    "UPDATE_NOT_WRITABLE" -> "没有权限替换应用，请手动安装"
    "UPDATE_INSTALL_FAILED" -> "安装未完成"
    "HTTP_403", "UPDATE_HTTP_403", "UPDATE_HTTP_429" -> "GitHub 限制了查询次数，稍后再试"
    else -> if (code.startsWith("UPDATE_HTTP_")) "GitHub 暂时无法访问" else "无法连接 GitHub"
}
