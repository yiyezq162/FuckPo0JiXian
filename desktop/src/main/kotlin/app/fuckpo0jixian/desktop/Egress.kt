package app.fuckpo0jixian.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.channels.Channel

internal interface Winsock : Library {
    fun setsockopt(s: Long, level: Int, name: Int, value: IntByReference, length: Int): Int
}

/**
 * Windows does not route by a bound source address once a TUN (Clash, sing-box …) holds the default route:
 * the packet still enters the tunnel and the reply is dropped. IP_UNICAST_IF pins the outgoing interface,
 * the same way mihomo sends its own DIRECT traffic. macOS scoped routing already honours bind().
 */
internal object Egress {
    private const val IPPROTO_IP = 0
    private const val IP_UNICAST_IF = 31
    private val winsock by lazy { runCatching { Native.load("Ws2_32", Winsock::class.java) }.getOrNull() }
    /** Needs --add-exports java.base/sun.nio.ch=ALL-UNNAMED (set in desktop/build.gradle.kts). */
    private val fdVal by lazy { runCatching { Class.forName("sun.nio.ch.SelChImpl").getMethod("getFDVal") }.getOrNull() }

    /** True when the socket is pinned to [local]'s interface; elsewhere than Windows nothing is needed. */
    fun pin(channel: Channel, local: InetAddress): Boolean {
        if (os != Os.WINDOWS) return true
        val index = runCatching { NetworkInterface.getByInetAddress(local)?.index }.getOrNull() ?: return false
        val socket = runCatching { fdVal?.invoke(channel) as? Int }.getOrNull() ?: return false
        // The option takes the interface index in network byte order.
        return winsock?.setsockopt(socket.toLong(), IPPROTO_IP, IP_UNICAST_IF, IntByReference(Integer.reverseBytes(index)), 4) == 0
    }
}
