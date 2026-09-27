package app.fuckpo0jixian

/** Display-only network names; the controller's raw kind strings feed core decisions and stay unchanged. */
fun networkText(kind: String): String = when {
    kind == "Wi-Fi" -> "Wi-Fi"
    kind.startsWith("移动数据") -> "移动数据"
    kind.startsWith("VPN") -> "VPN"
    kind == "其他网络" -> "其他网络"
    else -> "未连接"
}
