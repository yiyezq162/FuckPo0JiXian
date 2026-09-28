package app.fuckpo0jixian.core

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class UpdatesTest {
    private val base = "https://github.com/${Updates.REPO}/releases/download"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val apk = "new apk".toByteArray()
    private val json = """[
      {"tag_name":"v0.9.0-preview","draft":true,"body":"","assets":[{"name":"FuckPo0JiXian-0.9.0-preview.apk","browser_download_url":"$base/v0.9.0-preview/FuckPo0JiXian-0.9.0-preview.apk","size":1}]},
      {"tag_name":"v0.8.1-preview","draft":false,"prerelease":true,"html_url":"https://github.com/${Updates.REPO}/releases/tag/v0.8.1-preview",
       "body":"## 改动\n- 修复 [名额条](https://x) 顺序\n- 新增检查更新\n\n**Full Changelog**: https://x",
       "assets":[{"name":"FuckPo0JiXian-0.8.1-preview.apk","browser_download_url":"$base/v0.8.1-preview/FuckPo0JiXian-0.8.1-preview.apk","size":${apk.size}},
                 {"name":"SHA256SUMS-runtime08","browser_download_url":"$base/v0.8.1-preview/SHA256SUMS-runtime08","size":100}]},
      {"tag_name":"v0.8.0-preview","draft":false,"body":"","assets":[
                 {"name":"FuckPo0JiXian-Desktop-0.8.0-preview-macos-arm64.dmg","browser_download_url":"$base/v0.8.0-preview/a.dmg","size":3,"digest":"sha256:ABC"},
                 {"name":"FuckPo0JiXian-0.8.0-preview.apk","browser_download_url":"https://evil.example/FuckPo0JiXian-0.8.0-preview.apk","size":3}]},
      {"tag_name":"v0.7.6-preview","draft":false,"body":"","assets":[]}
    ]"""

    @Test fun versionsCompareByNumbers() {
        assertTrue(Updates.newer("0.8.0-preview", "0.7.6-preview"))
        assertTrue(Updates.newer("0.10.0-preview", "0.9.9-preview"))
        assertFalse(Updates.newer("0.7.6-preview", "0.7.6-preview"))
        assertFalse(Updates.newer("0.7.5", "0.7.6-preview"))
        assertTrue(Updates.newer("0.7.6-preview", "dev"))
        assertFalse(Updates.newer("nightly", "0.7.6"))
    }

    @Test fun picksTheNewestPublishedReleaseWithThisDevicesInstaller() {
        val releases = Updates.parse(json)
        assertEquals(listOf("0.8.1-preview", "0.8.0-preview", "0.7.6-preview"), releases.map { it.version }, "drafts are skipped")
        assertNull(releases[1].assets.find { it.name.endsWith(".apk") }, "downloads outside the repository are dropped")
        assertEquals("abc", releases[1].assets.single().sha256)
        val android = Updates.pick(releases, "0.7.6-preview", Updates::androidAsset)!!
        assertEquals("0.8.1-preview", android.version)
        val mac = Updates.pick(releases, "0.7.6-preview") { Updates.desktopAsset(it, "macos", "arm64", "dmg") }!!
        assertEquals("0.8.0-preview", mac.version, "0.8.1 has no Mac build, so 0.8.0 is the newest for this Mac")
        assertNull(Updates.pick(releases, "0.8.1-preview", Updates::androidAsset))
        assertNull(Updates.pick(releases, "0.7.6-preview") { Updates.desktopAsset(it, "windows", "x64", "msi") })
        assertEquals("修复 名额条 顺序\n新增检查更新", Updates.summary(releases[0].notes))
    }

    @Test fun checksumFilesAreRead() {
        val sums = "${"a".repeat(64)}  FuckPo0JiXian-0.8.1-preview.apk\n${"b".repeat(64)} *FuckPo0JiXian-Runtime.zip\n"
        assertEquals("a".repeat(64), Updates.checksum(sums, "FuckPo0JiXian-0.8.1-preview.apk"))
        assertEquals("b".repeat(64), Updates.checksum(sums, "FuckPo0JiXian-Runtime.zip"))
        assertNull(Updates.checksum(sums, "other.apk"))
    }

    /** Serves fixed bodies by URL, like GitHub's API and release downloads. */
    private fun client(bodies: Map<String, ByteArray>) = UpdateClient("test") { url ->
        object : HttpURLConnection(URL(url)) {
            val body = bodies[url]
            override fun getResponseCode() = if (body == null) 404 else 200
            override fun getInputStream() = ByteArrayInputStream(body ?: ByteArray(0))
            override fun getContentLengthLong() = body?.size?.toLong() ?: -1
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
        }
    }

    @Test fun downloadIsVerifiedAgainstTheReleaseChecksums() {
        val dir = Files.createTempDirectory("upd").toFile()
        val apkUrl = "$base/v0.8.1-preview/FuckPo0JiXian-0.8.1-preview.apk"
        val sumsUrl = "$base/v0.8.1-preview/SHA256SUMS-runtime08"
        val good = client(mapOf(Updates.API to json.toByteArray(), apkUrl to apk,
            sumsUrl to "${sha(apk)}  FuckPo0JiXian-0.8.1-preview.apk\n".toByteArray()))
        val update = good.check("0.7.6-preview", Updates::androidAsset)!!
        var last = 0L
        val file = good.download(update, dir) { done, _ -> last = done }
        assertContentEquals(apk, file.readBytes()); assertEquals(apk.size.toLong(), last)

        val tampered = client(mapOf(apkUrl to "evil".toByteArray(), sumsUrl to "${sha(apk)}  FuckPo0JiXian-0.8.1-preview.apk".toByteArray()))
        File(dir, update.asset.name).delete()
        assertEquals("UPDATE_CHECKSUM_MISMATCH", assertFailsWith<ApiFailure> { tampered.download(update, dir) }.code)
        assertTrue(dir.listFiles()!!.isEmpty(), "a bad download is not kept")

        val unsummed = client(mapOf(apkUrl to apk))
        assertEquals("UPDATE_NO_CHECKSUM", assertFailsWith<ApiFailure> { unsummed.download(update, dir) }.code)
    }

    /** Opt-in: FUCKPO0JIXIAN_LIVE=1 reads the real release list and downloads the newest APK, verified. */
    @Test fun liveReleaseDownloadVerifies() {
        if (System.getenv("FUCKPO0JIXIAN_LIVE") != "1") return
        val client = UpdateClient("FuckPo0JiXian-test")
        val update = client.check("0.7.0-preview", Updates::androidAsset)!!
        val file = client.download(update, Files.createTempDirectory("live").toFile())
        println("latest ${update.version} ${file.name} ${file.length()} bytes sha=${Updates.sha256(file)}")
        assertEquals(update.asset.sha256 ?: client.expectedSha(update), Updates.sha256(file))
        for (platform in listOf("macos" to "arm64" to "dmg", "windows" to "x64" to "msi")) {
            val (pa, ext) = platform
            assertNotNull(client.check("0.7.0-preview") { Updates.desktopAsset(it, pa.first, pa.second, ext) }, "$pa installer in the release")
        }
    }
    @Test fun finalReleaseIsNewerThanItsPreviewButNotTheOtherWayRound() {
        assertTrue(Updates.newer("0.8.3", "0.8.3-preview"))
        assertFalse(Updates.newer("0.8.3-preview", "0.8.3"))
        assertFalse(Updates.newer("0.8.3-beta", "0.8.3-preview"))
        assertFalse(Updates.newer("0.8.3", "0.8.3"))
        assertTrue(Updates.newer("0.8.4-preview", "0.8.3"))
        val releases = listOf(Release("0.8.3", "", "", listOf(ReleaseAsset("a-0.8.3", "u", 1))),
            Release("0.8.3-preview", "", "", listOf(ReleaseAsset("a-0.8.3-preview", "u", 1))))
        assertEquals("0.8.3", Updates.pick(releases, "0.8.3-preview") { "a-$it" }?.version)
        assertEquals("0.8.3", Updates.pick(releases.reversed(), "0.8.2") { "a-$it" }?.version)
    }
}
