package app.allowmate.core

import java.net.URI

object Po0Credential {
    private val token = Regex("pgnfw_[A-Za-z0-9_-]+")
    fun extract(value: String): String? {
        val input = value.trim()
        if (token.matches(input)) return input
        return runCatching {
            val uri = URI(input)
            if (uri.scheme != "https" || uri.host != "124.221.69.228" || uri.userInfo != null ||
                uri.port !in listOf(-1, 443) || uri.fragment != null) return null
            Regex("/api/firewall/(pgnfw_[A-Za-z0-9_-]+)(?:/add)?/?").matchEntire(uri.path)?.groupValues?.get(1)
        }.getOrNull()
    }
}
