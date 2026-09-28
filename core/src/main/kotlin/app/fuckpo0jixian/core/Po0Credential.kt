package app.fuckpo0jixian.core

import java.net.URI

/** What people paste: a bare token, or the official API link, which also names the server it lives on. */
data class Po0Link(val token: String, val endpoint: String?)

object Po0Credential {
    /** The server Po0's official scripts use; a pasted link may name another one. */
    const val DEFAULT_ENDPOINT = "https://124.221.69.228"
    private val token = Regex("pgnfw_[A-Za-z0-9_-]+")
    private val endpoint = Regex("https://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")

    fun validEndpoint(value: String) = endpoint.matches(value)

    fun parse(value: String): Po0Link? {
        val input = value.trim()
        if (token.matches(input)) return Po0Link(input, null)
        return runCatching {
            val uri = URI(input)
            val host = uri.host
            if (uri.scheme != "https" || host.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null ||
                uri.port !in -1..65535) return null
            val found = Regex("/api/firewall/(pgnfw_[A-Za-z0-9_-]+)(?:/add)?/?").matchEntire(uri.path)?.groupValues?.get(1) ?: return null
            val server = "https://" + host.lowercase() + if (uri.port in listOf(-1, 443)) "" else ":${uri.port}"
            if (validEndpoint(server)) Po0Link(found, server) else null
        }.getOrNull()
    }

    /** The token alone, as input validation needs it. */
    fun extract(value: String): String? = parse(value)?.token
}
