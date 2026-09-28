package app.fuckpo0jixian.core

import kotlinx.coroutines.sync.Mutex

interface StateStore { fun load(): State; fun save(state: State) }
class MemoryStore(var state: State = State()) : StateStore {
    override fun load() = state
    override fun save(state: State) { this.state = state }
}
/** What [NetworkSession.kind] holds: the transport a check leaves through, never a display label. */
object NetworkKind {
    const val WIFI = "wifi"
    const val CELLULAR = "cellular"
    /** A desktop network identified by its router, matched like a Wi-Fi AP. */
    const val LAN = "lan"
    const val OTHER = "other"
}
data class NetworkSession(val key: String, val kind: String,
                          val observedCidr: Cidr? = null, val wifi: WifiObservation? = null,
                          val manualPermit: ManualPermit? = null, val revalidate: suspend () -> Boolean = { true },
                          val stillCurrent: () -> Boolean)

class Engine(private val store: StateStore, private val now: () -> Long = System::currentTimeMillis,
             val policy: Policy = Policy()) {
    private val mutex = Mutex()
    fun state() = store.load()
    /** Single flight: a check that finds another one running returns BUSY instead of waiting. */
    suspend fun check(platform: SlotPlatform, network: NetworkSession, manual: Boolean = false, observeOnly: Boolean = false): String {
        if (!mutex.tryLock()) return "BUSY"
        try { return LayoutSync(store, now, policy).check(platform, network, observeOnly, manual) }
        finally { mutex.unlock() }
    }
}

/** Monotonic times supplied by caller; duplicate callbacks do not reset a stable deadline. */
class Debouncer(private val delayMs: Long = Policy().debounceMs) {
    private var key: String? = null
    private var deadline = Long.MAX_VALUE
    private var emitted = false
    fun changed(next: String?, time: Long): Boolean {
        if (key == next) return false
        key = next; deadline = if (next == null) Long.MAX_VALUE else time + delayMs; emitted = false
        return true
    }
    fun remaining(time: Long): Long = if (key == null) Long.MAX_VALUE else (deadline - time).coerceAtLeast(0)
    fun takeDue(time: Long): String? {
        if (emitted || time < deadline) return null
        emitted = true
        return key
    }
}
