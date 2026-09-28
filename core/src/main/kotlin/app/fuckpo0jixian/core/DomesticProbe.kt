package app.fuckpo0jixian.core

enum class ProbeSource(val url: String) {
    IP3322("https://ip.3322.net"), IPV4_IPW("https://4.ipw.cn"), IPIP("https://myip.ipip.net"),
    /** STUN binding over the physical network (desktop LAN interface, Android bound socket); not an HTTP source. */
    STUN("stun:stun.miwifi.com")
}
data class DomesticExit(val ipv4: String, val time: Long, val networkKey: String, val source: ProbeSource = ProbeSource.IPIP) {
    val cidr: Cidr get() = Cidr(ipv4.split('.').take(3).joinToString(".") + ".0/24")
    init { require(validIpv4(ipv4)) }
    companion object {
        fun validIpv4(value: String): Boolean {
            val parts = value.split('.')
            return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 && it == it.toIntOrNull().toString() }
        }
    }
}

/** On-demand source-IP observation through the application's existing routing rules. No credential. */
object DomesticProbe {
    fun parse(reply: HttpReply, time: Long, networkKey: String, source: ProbeSource = ProbeSource.IPIP): DomesticExit {
        if (reply.status != 200) throw ApiFailure("PROBE_HTTP_${reply.status}", reply.status, Wire.retryAfter(reply.retryAfter, time))
        val address = if (source != ProbeSource.IPIP) reply.body.trim() else {
            val matches = Regex("当前\\s*IP\\s*[：:]\\s*([0-9a-fA-F:.]+)").findAll(reply.body).toList()
            if (matches.size != 1) throw ApiFailure("PROBE_INVALID_RESPONSE")
            matches.single().groupValues[1]
        }
        if (address.contains(':')) throw ApiFailure("PROBE_IPV6_ONLY")
        return try { DomesticExit(address, time, networkKey, source) }
        catch (_: IllegalArgumentException) { throw ApiFailure("PROBE_INVALID_RESPONSE") }
    }
}
