package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Desktop counterpart of the Android controller. The desktop stays on while it runs, so instead of system wake-ups
 * it polls the local network every few seconds (no Po0 request) and applies the same 3 s / fallback / 1 h cadence;
 * the fallback interval is the device's own setting (2–59 minutes, 10 by default).
 */
class DesktopController(val store: FileStore = FileStore(), val vault: TokenVault = TokenVault.create(),
                        /** Seams for tests; the app always uses the real network and bound sockets. */
                        private val network: () -> DesktopLink = DesktopNetwork::read,
                        /** Public IPv4 seen directly from the LAN interface, or null to fall back to the HTTPS probe. */
                        private val stun: (localIp: String) -> String? = Stun::query,
                        private val now: () -> Long = System::currentTimeMillis,
                        /** tunnel = true only for Po0: see BoundTransport. The exit probe is always direct. */
                        private val transportFor: (localIp: String, tunnel: Boolean) -> Transport = ::BoundTransport) {
    val policy = Policy()
    private val engine = Engine(store, now, policy)
    val busy = MutableStateFlow(false)
    val feedback = MutableStateFlow("")
    val link = MutableStateFlow<DesktopLink?>(null)
    val credentialPresent = MutableStateFlow(runCatching { vault.exists() }.getOrDefault(false))
    val manualPreview = MutableStateFlow<ManualPermit?>(null)
    /** Problems worth a system notification, already worded for people. */
    val alerts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private var operation: Job? = null
    private var followUp: Job? = null
    private var previewSlot: Int? = null
    val version: String = javaClass.getResource("/version.txt")?.readText()?.trim() ?: "dev"
    val prefs = DesktopPrefs(store.dir)
    val notify = MutableStateFlow(prefs.notify)
    val updater = Updater(version, store.dir, prefs)
    /** Page the tray menu asked the window to show. */
    val requestedPage = MutableStateFlow<Page?>(null)
    /** Set by the app: quits so an installer can replace it. */
    var quit: () -> Unit = {}

    fun start() {
        if (store.load().deviceName.isBlank()) runCatching {
            store.save(store.load().copy(deviceName = when (os) { Os.MAC -> "Mac"; Os.WINDOWS -> "Windows"; Os.OTHER -> "电脑" }))
        }
        scope.launch { monitor() }
        // Daily update check in the background; nothing is downloaded without a click.
        scope.launch { while (isActive) { updater.check(this, manual = false); delay(3_600_000) } }
    }
    fun checkForUpdates() = updater.check(scope, manual = true)
    fun installUpdate(update: Update) = updater.install(scope, update) { quit() }
    fun notify(value: Boolean) { prefs.notify = value; notify.value = value }

    private fun scheduleEnabled(s: State = store.load()) = !s.paused && !s.demo && !s.authBlocked && s.globalBlock == null && credentialPresent.value

    private suspend fun monitor() {
        var lastKey: String? = null
        var lastTick = now()
        var lastFallback = lastTick
        while (currentCoroutineContext().isActive) {
            val current = runCatching { withContext(Dispatchers.IO) { network() } }.getOrNull()
            link.value = current
            val t = now()
            // A long gap between ticks means the computer slept; the network may have changed underneath.
            val woke = t - lastTick > 60_000
            lastTick = t
            if (current?.key != lastKey || woke) {
                lastKey = current?.key
                manualPreview.value = null
                if (current?.online == true && scheduleEnabled()) {
                    feedback.value = "网络已变化，正在检查"
                    delay(policy.debounceMs)
                    if (runCatching { withContext(Dispatchers.IO) { network() } }.getOrNull()?.key == lastKey) {
                        launchCheck(manual = false); lastFallback = now()
                    }
                }
            } else if (t - lastFallback >= store.load().fallbackMs && current?.online == true && scheduleEnabled()) {
                lastFallback = t
                launchCheck(manual = false, fallback = true)
            }
            delay(5_000)
        }
    }

    private fun launchCheck(manual: Boolean, observeOnly: Boolean = false, fallback: Boolean = false) {
        if (operation?.isActive == true) return
        operation = scope.launch { runCheck(manual, observeOnly, fallback) }
    }
    fun check() = launchCheck(manual = true)
    /** Compares the exit only (STUN, else ip.3322.net); Po0 is not asked. */
    fun checkDomestic() {
        if (operation?.isActive == true) return
        operation = scope.launch {
            if (!gate.tryLock()) return@launch
            busy.value = true
            try {
                val l = withContext(Dispatchers.IO) { network() }
                link.value = l
                feedback.value = when {
                    store.load().paused -> "请先恢复检查"
                    !l.online -> "当前没有可用网络"
                    !LocalCheck.probeAllowed(store.load(), l.key, now()) -> "查询太频繁，请稍后再试"
                    updateDomestic(l) -> "直连出口 ${store.load().domesticExit?.ipv4}"
                    else -> "查询失败，保留上次结果"
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback.value = "查询失败，保留上次结果" }
            finally { busy.value = false; gate.unlock() }
        }
    }
    fun checkConnection() = launchCheck(manual = true, observeOnly = true)

    private suspend fun updateDomestic(l: DesktopLink): Boolean {
        val t = now()
        val state = store.load().copy(nextProbeAllowed = t + policy.probeIntervalMs, probeStatus = "PROBE_RUNNING")
        withContext(Dispatchers.IO) { store.save(state) }
        return try {
            val observed = observeExit(l, t)
            withContext(Dispatchers.IO) { store.save(store.load().copy(domesticExit = observed, probeStatus = "PROBE_OBSERVED")) }
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val wait = maxOf(60_000, (e as? ApiFailure)?.retryAfterMs ?: 0)
            withContext(Dispatchers.IO) { store.save(store.load().copy(probeStatus = (e as? ApiFailure)?.code ?: "PROBE_FAILED",
                nextProbeAllowed = if (Long.MAX_VALUE - t < wait) Long.MAX_VALUE else t + wait)) }
            false
        }
    }

    /** STUN over pinned UDP first (direct even under a Windows TUN), then ip.3322.net over the pinned direct route. */
    private suspend fun observeExit(l: DesktopLink, now: Long): DomesticExit {
        withContext(Dispatchers.IO) { stun(l.localIp!!) }?.let { return DomesticExit(it, now, l.key, ProbeSource.STUN) }
        return DomesticProbe.parse(transportFor(l.localIp!!, false).execute("GET", ProbeSource.IP3322.url), now, l.key, ProbeSource.IP3322)
    }

    suspend fun runCheck(manual: Boolean, observeOnly: Boolean = false, fallback: Boolean = false): String {
        if (!gate.tryLock()) return "BUSY"
        busy.value = true
        try {
            val s = store.load()
            if (!credentialPresent.value) { feedback.value = statusText("NO_TOKEN"); return "NO_TOKEN" }
            val l = withContext(Dispatchers.IO) { network() }
            link.value = l
            if (!l.online) { feedback.value = "当前没有可用网络"; return "OFFLINE" }
            // Every request of this check leaves through this interface, whatever VPN / TUN / proxy is running.
            val transport = transportFor(l.localIp!!, true)
            val permit = manualPreview.value
            fun same() = runCatching { network() }.getOrNull()?.key == l.key
            var session = NetworkSession(l.key, l.kind, false, wifi = l.observation(now()), manualPermit = permit) { same() }
            if (fallback && !manual && !observeOnly) {
                // Every tick compares the exit again; Po0 only hears about it when something moved.
                if (LocalCheck.fallbackProbe(store.load(), l.key, now(), policy)) updateDomestic(l)
                if (LocalCheck.canSkipRemote(store.load(), store.load().domesticExit, l.key, now(), policy)) {
                    feedback.value = statusText("LOCAL_UNCHANGED"); return "LOCAL_UNCHANGED"
                }
            }
            val platform = Po0Platform(vault::read, transport)
            if ((!observeOnly || previewSlot != null) && (s.mode == Mode.AUTO || permit != null || previewSlot != null) && s.layout != null &&
                !s.paused && !s.authBlocked && (now() >= s.nextAllowed || (manual && s.status != "HTTP_429"))) {
                val d = store.load().domesticExit
                val fresh = d != null && d.networkKey == l.key && now() - d.time < policy.freshnessMs
                if (!fresh && LocalCheck.probeAllowed(store.load(), l.key, now())) updateDomestic(l)
                val observed = store.load().domesticExit?.takeIf {
                    it.networkKey == l.key && now() - it.time < policy.freshnessMs && store.load().probeStatus == "PROBE_OBSERVED"
                }?.cidr
                session = session.copy(observedCidr = observed, revalidate = {
                    val again = withContext(Dispatchers.IO) { network() }
                    val exit = observeExit(l, now())
                    again.key == l.key && exit.cidr == observed
                })
            }
            val code = withContext(Dispatchers.IO) { engine.check(platform, session, manual, observeOnly) }
            if (!observeOnly) manualPreview.value = null
            val after = store.load()
            previewSlot?.let { number ->
                val slot = after.layout?.slots?.find { it.number == number }
                if (code in setOf("PRESENT_CURRENT_CHECK", "OBSERVED_MISSING") && !after.paused && slot?.purpose == SlotPurpose.FIXED &&
                    slot.writer == Writer.LOCAL && slot.authorized && session.observedCidr == after.snapshot?.current && same()) {
                    manualPreview.value = ManualPermit(after.accountContext, after.layout!!.version, number, l.key, session.wifi,
                        after.snapshot!!, now() + 180_000)
                } else feedback.value = "无法预览：请确认已恢复检查、此槽已授权且出口未变"
            }
            if (!after.paused && after.lastCheck > s.lastCheck && now() >= after.nextProbeAllowed &&
                (manual || after.domesticExit?.networkKey != l.key || now() - (after.domesticExit?.time ?: 0) >= policy.cacheMs)) {
                updateDomestic(l)
            }
            feedback.value = statusText(code)
            if (!manual && SyncPlanning.needsFollowUp(code, store.load())) scheduleFollowUp()
            if (observeOnly && after.mode == Mode.AUTO && after.layout != null && !after.paused && code == "OBSERVED_MISSING" && previewSlot == null)
                scheduleFollowUp()
            if (code in listOf("HTTP_401", "HTTP_403", "CAPACITY_FULL", "SLOT_CONFLICT", "SLOT_VERIFY_FAILED", "COVERED_OTHER_SLOT",
                    "IDENTITY_AMBIGUOUS", "PENDING_REVIEW", "SHARED_RECENT")) {
                val latest = store.load()
                val marker = "NOTIFIED:$code:${latest.layout?.version}"
                if (latest.layout != null && marker !in latest.layout!!.notices) {
                    store.save(latest.copy(layout = latest.layout!!.copy(notices = (latest.layout!!.notices + marker).toList().takeLast(100).toSet())))
                    if (notify.value) alerts.tryEmit(desktopText(code))
                }
            }
            return code
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { feedback.value = "无法读取本地数据，已停止检查"; return "LOCAL_ERROR" }
        finally { busy.value = false; gate.unlock() }
    }

    private fun scheduleFollowUp() {
        followUp?.cancel()
        followUp = scope.launch {
            delay(maxOf(policy.debounceMs, store.load().nextAllowed - now()))
            if (scheduleEnabled()) launchCheck(manual = false)
        }
    }

    private fun edit(scheduleNow: Boolean = false, done: () -> String = { "已保存" }, block: (State) -> State) {
        scope.launch {
            manualPreview.value = null
            operation?.cancelAndJoin()
            gate.withLock {
                try { withContext(Dispatchers.IO) { store.save(block(store.load())) }; feedback.value = done() }
                catch (e: IllegalArgumentException) { feedback.value = statusText(e.message ?: "CONFIG_INVALID") }
                catch (_: Exception) { feedback.value = "保存失败" }
            }
            if (scheduleNow && scheduleEnabled()) launchCheck(manual = false)
        }
    }
    fun pause(value: Boolean) = edit(scheduleNow = !value) { it.copy(paused = value) }
    fun mode(mode: Mode) = edit(scheduleNow = true) { it.copy(mode = mode, lastSuccess = 0) }
    fun deviceName(value: String) = edit { it.copy(deviceName = value.trim().take(24)) }
    /** Takes effect at the next monitor tick; the hourly Po0 refresh is not adjustable. */
    fun fallbackMinutes(value: Int) = edit { it.copy(fallbackMinutes = FallbackInterval.clamp(value)) }
    fun configureSlot(slot: ManagedSlot, acknowledged: Boolean) = edit(scheduleNow = true) {
        LayoutRules.saveSlot(it, slot, acknowledged, now())
    }
    /** Binds the router this computer is connected to right now. */
    fun bindNetwork(slot: Int, name: String) = edit(scheduleNow = true) {
        val observation = network().observation(now())
        require(observation != null) { "GATEWAY_UNAVAILABLE" }
        LayoutRules.bind(it, slot, observation, now(), name, false).copy(lastSuccess = 0)
    }
    fun revokeNetwork(slot: Int) = edit(scheduleNow = true) { LayoutRules.revoke(it, slot).copy(lastSuccess = 0) }
    fun importPeers(peer: PeerLayout) {
        var count = 0
        edit(done = { if (count == 0) "没有需要更新的槽位" else "已标注 $count 个槽位" }) {
            PeerImport.apply(it, peer).also { r -> count = r.changed.size }.state
        }
    }
    fun exportText(): String = RedactedExport.build(store.load(), if (os == Os.WINDOWS) "windows" else "macos", version, now(), BoundTransport.trace())
    fun saveToken(value: String) = edit {
        val sameAccount = runCatching { vault.read() }.getOrNull() == value.trim()
        vault.save(value.trim())
        credentialPresent.value = true
        it.copy(paused = true, authBlocked = false, lastSuccess = 0, snapshot = if (sameAccount) it.snapshot else null,
            layout = if (sameAccount) it.layout else null, accountContext = if (sameAccount) it.accountContext else UUID.randomUUID().toString(),
            globalBlock = if (sameAccount) it.globalBlock else null, status = "TOKEN_SAVED")
    }
    fun clearToken() = edit {
        vault.clear(); credentialPresent.value = false
        State(paused = true, nextAllowed = it.nextAllowed, nextProbeAllowed = it.nextProbeAllowed, status = "NO_TOKEN", deviceName = it.deviceName,
            fallbackMinutes = it.fallbackMinutes)
    }
    fun clearHistory() = edit { it.copy(events = emptyList(), observations = emptyList(), domesticExit = null, probeStatus = "NOT_CHECKED") }
    fun previewManual(number: Int) {
        if (busy.value || operation?.isActive == true) return
        operation = scope.launch {
            manualPreview.value = null
            previewSlot = number
            try { runCheck(true, observeOnly = true) } finally { previewSlot = null }
        }
    }
    fun cancelManual() { manualPreview.value = null }
    fun confirmManual() {
        val permit = manualPreview.value ?: return
        if (operation?.isActive == true) return
        operation = scope.launch {
            feedback.value = "已确认，即将更新"
            delay((store.load().nextAllowed - now()).coerceAtLeast(0))
            if (manualPreview.value == permit) runCheck(true)
            manualPreview.value = null
        }
    }
    fun stop() { scope.cancel() }
}
