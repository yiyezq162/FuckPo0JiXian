package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Desktop counterpart of the Android controller. The desktop stays on while it runs, so instead of system wake-ups
 * it watches the local network (no Po0 request) and applies the same 3 s / fallback / 1 h cadence; the fallback
 * interval is the device's own setting (2–59 minutes, 10 by default). Every few seconds it only lists the interfaces
 * in-process; the route and router are read again when that list changes, after sleep, and every [FULL_READ_MS].
 */
class DesktopController(val store: FileStore = FileStore(), val vault: TokenVault = TokenVault.create(),
                        /** Seams for tests; the app always uses the real network and bound sockets. */
                        private val network: () -> DesktopLink = DesktopNetwork::read,
                        /** Cheap in-process change hint; when it moves, [network] is read again. */
                        private val hint: () -> String = DesktopNetwork::fingerprint,
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
    val activity = ActivityLog(store.dir)
    /** Page the tray menu asked the window to show. */
    val requestedPage = MutableStateFlow<Page?>(null)
    /** Set by the app: quits so an installer can replace it. */
    var quit: () -> Unit = {}

    fun start() {
        activity.add("START $version ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        // Every saved change, one line per fact, for 导出调试信息.
        scope.launch {
            var previous = store.flow.value
            store.flow.collect { next -> StateDiff.describe(previous, next).forEach { activity.add("STATE $it") }; previous = next }
        }
        if (store.load().deviceName.isBlank()) runCatching {
            store.save(store.load().copy(deviceName = when (os) { Os.MAC -> "Mac"; Os.WINDOWS -> "Windows"; Os.OTHER -> "电脑" }))
        }
        scope.launch { monitor() }
        // Daily update check in the background; nothing is downloaded without a click.
        scope.launch { while (isActive) { updater.check(this, manual = false); delay(3_600_000) } }
    }
    fun checkForUpdates() = updater.check(scope, manual = true)
    /** The main window came into view (opened, shown from the tray or Dock, restored from minimized). */
    fun resumed() { activity.add("OPEN"); updater.resumed(scope) }
    fun installUpdate(update: Update) = updater.install(scope, update) { quit() }
    fun notify(value: Boolean) { prefs.notify = value; notify.value = value }
    val dockHidden = MutableStateFlow(prefs.hideDock)
    fun hideDock(value: Boolean) { prefs.hideDock = value; dockHidden.value = value; MacDock.show(!value) }

    private fun scheduleEnabled(s: State = store.load()) = !s.paused && !s.demo && !s.authBlocked && s.globalBlock == null && credentialPresent.value

    private suspend fun monitor() {
        var lastKey: String? = null
        var lastTick = now()
        var lastFallback = lastTick
        var lastHint: String? = null
        var lastRead = 0L
        var current: DesktopLink? = null
        while (currentCoroutineContext().isActive) {
            val t = now()
            // A long gap between ticks means the computer slept; the network may have changed underneath.
            val gap = t - lastTick
            val woke = gap > 60_000
            lastTick = t
            val hinted = runCatching { withContext(Dispatchers.IO) { hint() } }.getOrNull()
            if (hinted == null || hinted != lastHint || woke || t - lastRead >= FULL_READ_MS) {
                lastHint = hinted; lastRead = t
                current = runCatching { withContext(Dispatchers.IO) { network() } }.getOrNull()
                link.value = current
            }
            if (current?.key != lastKey || woke) {
                if (woke) activity.add("WOKE after ${gap / 1000}s")
                if (current?.key != lastKey) activity.add("NET ${current?.kind ?: "none"} iface=${current?.iface} ip=${current?.localIp} gw=${current?.gatewayIp}")
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
    fun check() { activity.add("USER check"); launchCheck(manual = true) }
    /** Compares the exit only (STUN, else ip.3322.net); Po0 is not asked. */
    fun checkDomestic() {
        if (operation?.isActive == true) return
        activity.add("USER checkDomestic")
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
    fun checkConnection() { activity.add("USER checkConnection"); launchCheck(manual = true, observeOnly = true) }
    fun reviewProtection() {
        manualPreview.value = null
        launchCheck(manual = true, observeOnly = true)
    }

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
        val began = now()
        val requests = BoundTransport.requests.get()
        val code = runCheckLocked(manual, observeOnly, fallback)
        if (code != "BUSY") activity.add("CHECK ${if (manual) "manual" else "auto"}${if (fallback) "-fallback" else ""}${if (observeOnly) "-read" else ""} " +
            "$code http=${BoundTransport.requests.get() - requests} ${now() - began}ms")
        return code
    }

    private suspend fun runCheckLocked(manual: Boolean, observeOnly: Boolean, fallback: Boolean): String {
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
            var session = NetworkSession(l.key, l.kind, wifi = l.observation(now()), manualPermit = permit) { same() }
            if (fallback && !manual && !observeOnly) {
                // Every tick compares the exit again; Po0 only hears about it when something moved.
                if (LocalCheck.fallbackProbe(store.load(), l.key, now(), policy)) updateDomestic(l)
                if (LocalCheck.canSkipRemote(store.load(), store.load().domesticExit, l.key, now(), policy)) {
                    feedback.value = statusText("LOCAL_UNCHANGED"); return "LOCAL_UNCHANGED"
                }
            }
            val platform = Po0Platform(vault::read, transport, endpoint = s.endpoint)
            if (CheckFlow.mayWrite(s, observeOnly, manual, previewSlot != null, permit, now())) {
                if (CheckFlow.freshExit(store.load(), l.key, now(), policy) == null && LocalCheck.probeAllowed(store.load(), l.key, now())) updateDomestic(l)
                val observed = CheckFlow.freshExit(store.load(), l.key, now(), policy)
                session = session.copy(observedCidr = observed, revalidate = {
                    val again = withContext(Dispatchers.IO) { network() }
                    val exit = observeExit(l, now())
                    again.key == l.key && exit.cidr == observed
                })
            }
            val code = withContext(Dispatchers.IO) { engine.check(platform, session, manual, observeOnly) }
            if (!observeOnly) manualPreview.value = null
            val after = store.load()
            feedback.value = statusText(code)
            previewSlot?.let { number ->
                manualPreview.value = CheckFlow.permit(code, after, number, l.key, session.wifi, session.observedCidr, same(), now())
                if (manualPreview.value == null) feedback.value = "无法预览：请确认已恢复检查、此槽已授权且出口未变"
            }
            if (CheckFlow.refreshExit(s, after, l.key, manual, now(), policy)) updateDomestic(l)
            if (!manual && SyncPlanning.needsFollowUp(code, store.load())) scheduleFollowUp()
            if (CheckFlow.syncAfterReading(code, after, observeOnly, previewSlot != null)) scheduleFollowUp()
            Alerts.after(s, code, store.load())?.let { if (Alerts.claim(store, it) && notify.value) alerts.tryEmit(desktopText(it.code)) }
            return code
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { feedback.value = "本次检查出错，请稍后重试"; return "LOCAL_ERROR" }
        finally { busy.value = false; gate.unlock() }
    }

    private fun scheduleFollowUp() {
        followUp?.cancel()
        followUp = scope.launch {
            delay(maxOf(policy.debounceMs, maxOf(store.load().nextAllowed, store.load().serverDeadline()) - now()))
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
    fun pause(value: Boolean) { activity.add("USER pause=$value"); edit(scheduleNow = !value) { it.copy(paused = value) } }
    fun mode(mode: Mode) { activity.add("USER mode=$mode"); edit(scheduleNow = true) { it.copy(mode = mode, lastSuccess = 0) } }
    fun deviceName(value: String) = edit { it.copy(deviceName = value.trim().take(24)) }
    /** Takes effect at the next monitor tick; the hourly Po0 refresh is not adjustable. */
    fun fallbackMinutes(value: Int) = edit { it.copy(fallbackMinutes = FallbackInterval.clamp(value)) }
    fun configureSlot(slot: ManagedSlot, acknowledged: Boolean) {
        activity.add("USER saveSlot ${slot.number}")
        edit(scheduleNow = true) { LayoutRules.saveSlot(it, slot, acknowledged, now()) }
    }
    /** Adds the router this computer is connected to right now to the slot's networks. */
    fun bindNetwork(slot: Int, name: String) = activity.add("USER bindNetwork $slot").let { edit(scheduleNow = true) {
        val observation = network().observation(now())
        require(observation != null) { "GATEWAY_UNAVAILABLE" }
        LayoutRules.bind(it, slot, observation, now(), name).copy(lastSuccess = 0)
    } }
    fun revokeNetwork(slot: Int) { activity.add("USER revokeNetwork $slot"); edit(scheduleNow = true) { LayoutRules.revoke(it, slot).copy(lastSuccess = 0) } }
    fun unbindNetwork(slot: Int, identityId: String) { activity.add("USER unbindNetwork $slot"); edit(scheduleNow = true) { LayoutRules.unbind(it, slot, identityId).copy(lastSuccess = 0) } }
    fun importPeers(peer: PeerLayout) {
        activity.add("USER import from=${peer.device} slots=${peer.slots.size}")
        var count = 0
        edit(done = { if (count == 0) "没有需要更新的槽位" else "已标注 $count 个槽位" }) {
            PeerImport.apply(it, peer).also { r -> count = r.changed.size }.state
        }
    }
    private val platform get() = if (os == Os.WINDOWS) "windows" else "macos"
    /** 共享分工 for the user's other devices: unmasked except that the Token is never part of the state. */
    fun shareText(): String = ShareExport.build(store.load(), platform, version, now())
    /** 调试信息: system facts, the current link and the full activity log, addresses cut to /16. */
    fun debugText(): String {
        activity.add("USER exportDebug")
        val l = link.value
        val device = mapOf("platform" to platform, "app" to version, "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            "arch" to System.getProperty("os.arch"), "java" to System.getProperty("java.version"),
            "autostart" to runCatching { Autostart.enabled() }.getOrNull()?.toString(), "notify" to notify.value.toString())
        val details = mapOf("link" to l?.let { "${it.kind} iface=${it.iface} ip=${it.localIp} gw=${it.gatewayIp} mac=${it.gatewayMac}" },
            "arpMiss" to DesktopNetwork.arpMiss,
            "busy" to busy.value.toString(), "update" to updater.state.value.label)
        return DebugExport.build(store.load(), device, now(), details = details,
            logs = mapOf("activity" to activity.recent(), "requests" to BoundTransport.trace()))
    }
    fun saveToken(value: String) = activity.add("USER saveToken").let { edit {
        val next = AccountCredentials.save(store, value, vault::read, vault::save)
        credentialPresent.value = true
        next
    } }
    fun clearToken() = activity.add("USER clearToken").let { edit {
        val empty = State(paused = true, nextAllowed = it.nextAllowed, serverNotBefore = it.serverDeadline(), nextProbeAllowed = it.nextProbeAllowed, status = "NO_TOKEN", deviceName = it.deviceName,
            fallbackMinutes = it.fallbackMinutes)
        store.save(empty)
        vault.clear(); credentialPresent.value = false
        empty
    } }
    fun clearHistory() = activity.add("USER clearHistory").let { edit { it.copy(events = emptyList(), observations = emptyList(), domesticExit = null, probeStatus = "NOT_CHECKED") } }
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
            // A server wait still holds; the confirmation expires rather than replaying offline.
            val wait = CheckFlow.confirmWait(store.load(), permit, now())
            if (wait == null) { feedback.value = statusText("HTTP_429"); manualPreview.value = null; return@launch }
            feedback.value = "已确认，即将更新"
            delay(wait)
            if (manualPreview.value == permit) runCheck(true)
            manualPreview.value = null
        }
    }
    fun stop() { scope.cancel() }

    companion object {
        /** A router swapped behind an unchanged address is noticed within this long. */
        const val FULL_READ_MS = 30_000L
    }
}
