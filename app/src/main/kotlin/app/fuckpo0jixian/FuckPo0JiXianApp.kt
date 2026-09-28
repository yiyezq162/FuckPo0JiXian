package app.fuckpo0jixian

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

class FuckPo0JiXianApp : Application() {
    lateinit var controller: Controller
    override fun onCreate() {
        super.onCreate()
        LifeLog.init(this); LifeLog.processStart()
        controller = Controller(this); controller.start()
    }
}
class Controller(private val context: Context) {
    val store = LocalStore(context)
    val vault = TokenVault(context)
    val runtime = RuntimeBridge(context)
    val updater = AppUpdater(context)
    val credentialPresent = MutableStateFlow(vault.exists())
    val policy = Policy()
    val engine = Engine(store, policy = policy)
    val busy = MutableStateFlow(false)
    val feedback = MutableStateFlow("")
    val networkLabel = MutableStateFlow("网络信息不可用")
    val currentNetworkKey = MutableStateFlow<String?>(null)
    val wifiObservation = MutableStateFlow<WifiObservation?>(null)
    val manualPreview = MutableStateFlow<ManualPermit?>(null)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    // Android net IDs may be reused after reboot. Never reuse a prior boot's success cache.
    private val bootEpoch = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }
        .getOrElse { UUID.randomUUID().toString() }
    private fun networkKey(network: Network?) = network?.let { "$bootEpoch:${it.networkHandle}" }
    private val wifiObserver = WifiIdentityObserver(context) { networkKey(it)!! }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private var operation: Job? = null
    private var pendingBackground = false
    @Volatile private var requestJob: Job? = null
    private var networkId: String? = null
    private var lastNetLine: String? = null
    private val stability = Debouncer(policy.debounceMs)
    private var demo = SlotDemoPlatform()
    private var demoScenario = 0
    private var previewSlot: Int? = null
    /** The best non-VPN network, reported on Android 12+ whatever VPN is the default. */
    @Volatile private var underlying: Network? = null
    /**
     * Every request is bound to the physical network and skips proxies (NetworkTransport), so a VPN / TUN such as
     * Clash neither carries it nor changes the exit Po0 and the probe see. Only a VPN that forbids bypassing, and
     * does not exclude this app, can refuse the binding; test that locally before sending anything.
     */
    private fun directPath(n: Network): Boolean = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true ||
        runCatching { java.net.DatagramSocket().use { n.bindSocket(it) } }.isSuccess

    fun start() {
        scope.launch {
            store.flow.map { Triple(it.runtimeMode, it.paused, RuntimePolicy.enabled(it)) }.distinctUntilChanged()
                .collect { runCatching { runtime.publish(store.load()) }.onFailure { runtime.status.value = "模块状态保存失败 · 已降级" } }
        }
        if (store.load().demo) restoreDemo()
        // Other devices show this name next to the slots this phone manages.
        if (store.load().deviceName.isBlank()) runCatching {
            // The name people gave the phone (e.g. "Xiaomi 14"), not a model code like 23127PN0CC.
            val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL.trim()
            store.save(store.load().copy(deviceName = name.take(24)))
        }
        wifiObserver.start { changed() }
        ContextCompat.registerReceiver(context, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                LifeLog.add("DOZE ${if (c.getSystemService(android.os.PowerManager::class.java).isDeviceIdleMode) "on" else "off"}")
            }
        }, android.content.IntentFilter(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshSchedule()
        // An app update finishes cancelling the old install's PendingIntents a few seconds after the new
        // process starts, taking the fresh network wake with it (seen on device). Re-arm once afterwards.
        scope.launch { delay(30_000); if (scheduleEnabled()) Wake.enable(context, fallbackMs()) }
        if (Build.VERSION.SDK_INT >= 31) runCatching { WifiIdentityObserver.registerPhysical(cm, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { underlying = network; changed() }
            override fun onLost(network: Network) { if (underlying == network) underlying = null; changed() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = changed()
        }) } else runCatching { cm.registerNetworkCallback(WifiIdentityObserver.PHYSICAL, object : ConnectivityManager.NetworkCallback() {
            // Physical networks coming, going or re-addressing underneath a VPN; legacyPhysical() picks among them.
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = changed()
        }) }
        try { cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
            // A new local address on the same network (mobile data re-attach, a new IPv6 prefix) usually means a new
            // public exit. changed() only reacts when the address key moves, so DNS or route updates cost nothing.
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = changed()
        }) } catch (_: Exception) { feedback.value = "无法监听网络变化，请手动检查" }
    }
    private fun restoreDemo() {
        demo = SlotDemoPlatform()
        store.load().snapshot?.let { demo.current = it.current; demo.capacity = it.capacity; demo.entries = it.entries; demo.revision = it.revision?.toIntOrNull() ?: 0 }
    }
    /** Wi-Fi or mobile data, underneath any VPN. */
    private fun current(): Network? = if (Build.VERSION.SDK_INT >= 31) underlying else legacyPhysical()
    /** Before Android 12: the default network, or under a VPN the validated Wi-Fi, else foreground mobile data. */
    private fun legacyPhysical(): Network? {
        val active = cm.activeNetwork
        if (cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return active
        val candidates = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }.filter { (_, c) ->
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        }
        return (candidates.firstOrNull { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } ?: candidates.firstOrNull())?.first
    }
    private fun physical(): Network? = current()?.takeIf { n ->
        cm.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
    }
    private fun kind(n: Network?): String = cm.getNetworkCapabilities(n)?.let {
        when { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN（不作为直连证明）"
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据（SIM 未采集）"
            else -> "其他网络" }
    } ?: "离线或网络不可用"
    private fun changed() { scope.launch {
        val n = physical(); networkLabel.value = kind(current() ?: cm.activeNetwork)
        currentNetworkKey.value = networkKey(current() ?: cm.activeNetwork)
        wifiObservation.value = if (store.load().demo) demoWifi() else wifiObserver.latest
        val identity = wifiObserver.latest
        val id = networkKey(n)?.plus(":${identity?.ssid}:${identity?.bssid}:${identity?.security}:${wifiObserver.permitted()}:${localAddresses(n)}")
        if (!stability.changed(id, SystemClock.elapsedRealtime())) return@launch
        // Credential- and identity-free timing evidence: network type and the same clock as FuckPo0JiXianCheck.
        val type = cm.getNetworkCapabilities(n)?.let { c -> when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"; else -> "other" } } ?: "none"
        android.util.Log.i("FuckPo0JiXianNet", "change type=$type atMs=${SystemClock.elapsedRealtime()}")
        val net = "NET $type vpn=${cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true}"
        if (net != lastNetLine) { lastNetLine = net; LifeLog.add(net) }
        manualPreview.value = null
        networkId = id
        operation?.cancel()
        requestJob?.cancel()
        operation?.join()
        requestJob?.join()
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork("network-check")
        wm.cancelUniqueWork("catch-up")
        if (n != null) scheduleCheck("network-check")
    } }
    /**
     * What identifies this network's addressing: its IPv4 addresses, or on IPv6-only mobile data (464XLAT, whose
     * IPv4 is a fixed 192.0.0.x on a stacked link) its global /64 prefixes. Prefixes, not addresses, so privacy
     * address rotation within a prefix does not trigger checks.
     */
    private fun localAddresses(n: Network?): String? {
        val addresses = n?.let { cm.getLinkProperties(it) }?.linkAddresses?.map { it.address } ?: return null
        val v4 = addresses.mapNotNull { (it as? java.net.Inet4Address)?.hostAddress }.sorted()
        if (v4.isNotEmpty()) return v4.joinToString(",")
        return addresses.filterIsInstance<java.net.Inet6Address>().filter { !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isLoopbackAddress }
            .map { it.address.take(8).joinToString("") { b -> "%02x".format(b) } }.distinct().sorted().joinToString(",")
    }
    /** This device's fallback interval (2–59 minutes, 10 by default). */
    private fun fallbackMs() = store.load().fallbackMs
    private fun scheduleCheck(name: String, minimumDelay: Long = 0) {
        if (!vault.exists() || physical() == null) return
        val planned = SyncPlanning.delayMs(store.load(), System.currentTimeMillis(), stability.remaining(SystemClock.elapsedRealtime())) ?: return
        val wait = maxOf(planned, minimumDelay)
        if (name == "network-check") feedback.value = if (wait > policy.debounceMs) "网络已变化，稍后自动检查" else "网络已变化，正在检查"
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CheckWorker>().setInitialDelay(wait, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    private fun scheduleEnabled(s: State = store.load()) = !s.paused && !s.demo && !s.authBlocked && s.globalBlock == null && vault.exists()
    private fun refreshSchedule() {
        val wm = WorkManager.getInstance(context)
        if (!scheduleEnabled()) {
            wm.cancelUniqueWork("fallback"); wm.cancelUniqueWork("fallback-now"); wm.cancelUniqueWork("network-check"); wm.cancelUniqueWork("catch-up")
            Wake.disable(context)
        } else {
            // Alarm chain gives the configured cadence (Doze stretches it to ~9 minutes at best); WorkManager, whose
            // period cannot go below 15 minutes, is the durable backstop.
            Wake.enable(context, fallbackMs())
            wm.enqueueUniquePeriodicWork("fallback", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CheckWorker>(maxOf(15L, fallbackMs() / 60_000), TimeUnit.MINUTES)
                    .setInputData(workDataOf(CheckWorker.TRIGGER to CheckWorker.FALLBACK))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        KeepAlive.sync(context, scheduleEnabled())
    }
    fun keepAlive(value: Boolean) { KeepAlive.set(context, value); refreshSchedule() }
    /** Fallback alarm: re-arm first so a failed check never breaks the chain, then run a cheap local comparison. */
    fun fallbackTick() {
        if (!scheduleEnabled()) { Wake.disable(context); return }
        Wake.enable(context, fallbackMs())
        WorkManager.getInstance(context).enqueueUniqueWork("fallback-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CheckWorker>().setInputData(workDataOf(CheckWorker.TRIGGER to CheckWorker.FALLBACK))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    private fun drainPendingCheck() {
        if (!pendingBackground) return
        pendingBackground = false
        val s = store.load()
        val fresh = !(s.mode == Mode.AUTO && s.status == "OBSERVED_MISSING") && s.networkKey == networkKey(physical()) && s.failures == 0 && s.lastSuccess > 0 &&
            System.currentTimeMillis() - s.lastSuccess < policy.cacheMs
        // A completed platform check already satisfies duplicate triggers. An exit-only
        // check or cancelled old-network request does not; coalesce those to one follow-up.
        if (!fresh) scheduleCheck("catch-up", policy.debounceMs)
    }
    fun check() { if (operation?.isActive == true) return; operation = scope.launch { runCheck(true) } }
    fun checkConnection() { if (operation?.isActive == true) return; operation = scope.launch { runCheck(true, observeOnly = true) } }
    fun checkDomestic() {
        if (operation?.isActive == true) return
        operation = scope.launch {
            if (!gate.tryLock()) return@launch
            val thisRequest = currentCoroutineContext()[Job]
            requestJob = thisRequest
            busy.value = true
            try {
                val state = store.load()
                val now = System.currentTimeMillis()
                if (state.demo || state.paused) { feedback.value = "请先恢复检查"; return@launch }
                if (now < state.nextProbeAllowed) { feedback.value = "查询太频繁，请稍后再试"; return@launch }
                val network = physical()
                if (network == null) { feedback.value = "当前没有可用网络"; return@launch }
                if (!withContext(Dispatchers.IO) { directPath(network) }) { feedback.value = statusText("VPN_NO_BYPASS"); return@launch }
                feedback.value = if (updateDomestic(network)) "已获取国内出口" else "查询失败，保留上次结果"
            } catch (_: CancellationException) {
                feedback.value = "查询已取消"
            } catch (_: Exception) {
                feedback.value = "无法保存查询结果"
            } finally {
                if (requestJob === thisRequest) requestJob = null
                busy.value = false; gate.unlock()
                drainPendingCheck()
            }
        }
    }
    private suspend fun updateDomestic(network: Network): Boolean {
        val now = System.currentTimeMillis()
        val state = store.load().copy(nextProbeAllowed = now + policy.probeIntervalMs, probeStatus = "PROBE_RUNNING")
        withContext(Dispatchers.IO) { store.save(state) }
        return try {
            val source = ProbeSource.IP3322
            val response = withContext(Dispatchers.IO) { NetworkTransport(network).execute("GET", source.url) }
            if (physical() != network) throw ApiFailure("NETWORK_CHANGED")
            val observed = DomesticProbe.parse(response, now, networkKey(network)!!, source)
            withContext(Dispatchers.IO) { store.save(state.copy(domesticExit = observed, probeStatus = "PROBE_OBSERVED")) }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { store.save(state.copy(probeStatus = "NETWORK_CHANGED")) }
            throw e
        } catch (e: Exception) {
            val wait = maxOf(60_000, (e as? ApiFailure)?.retryAfterMs ?: 0)
            withContext(Dispatchers.IO) {
                store.save(state.copy(probeStatus = (e as? ApiFailure)?.code ?: "PROBE_FAILED",
                    nextProbeAllowed = if (Long.MAX_VALUE - now < wait) Long.MAX_VALUE else now + wait))
            }
            false
        }
    }
    suspend fun runCheck(manual: Boolean, observeOnly: Boolean = false, enhanced: Boolean = false,
                         fallback: Boolean = false): String = withContext(Dispatchers.Main.immediate) {
        val began = SystemClock.elapsedRealtime()
        val requests = NetworkTransport.requests.get()
        val selected = store.load().runtimeMode.name
        val result = runCheckOnMain(manual, observeOnly, enhanced, fallback)
        // Bounded, credential-free evidence for standard/enhanced comparisons. BUSY
        // did not own the gate and must not count another operation's HTTP attempts.
        val count = if (result == "BUSY") 0 else NetworkTransport.requests.get() - requests
        val safeCode = result.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "UNKNOWN"
        val path = (if (enhanced) "enhanced" else if (manual) "manual" else "workmanager") + if (fallback) "-fallback" else ""
        android.util.Log.i("FuckPo0JiXianCheck", "uid=${android.os.Process.myUid()} mode=$selected path=$path " +
            "beginMs=$began endMs=${SystemClock.elapsedRealtime()} httpAttempts=$count result=$safeCode")
        if (result != "BUSY") LifeLog.add("CHECK $path $safeCode http=$count ${SystemClock.elapsedRealtime() - began}ms")
        // The network has settled by now: arm the one-shot wake for the next change and restart the fallback clock.
        if (result != "BUSY" && result != "CANCELLED_NETWORK_OR_SETTINGS" && scheduleEnabled()) Wake.enable(context, fallbackMs())
        result
    }
    private suspend fun runCheckOnMain(manual: Boolean, observeOnly: Boolean, enhanced: Boolean, fallback: Boolean): String {
        if (!gate.tryLock()) { if (!manual) pendingBackground = true; return "BUSY" }
        val thisRequest = currentCoroutineContext()[Job]
        requestJob = thisRequest
        busy.value = true
        try {
            val s = store.load()
            if (enhanced && !RuntimePolicy.enabled(s)) return "ENHANCED_STOPPED"
            if (enhanced && System.currentTimeMillis() < s.nextAllowed) {
                scheduleCheck("catch-up")
                return "RATE_LIMITED"
            }
            val n = physical()
            if (!s.demo && n == null) { feedback.value = statusText("UNTRUSTED_PATH"); return "UNTRUSTED_PATH" }
            if (!s.demo && !withContext(Dispatchers.IO) { directPath(n!!) }) { feedback.value = statusText("VPN_NO_BYPASS"); return "VPN_NO_BYPASS" }
            val wifi = if (s.demo) demoWifi() else if (kind(n) == "Wi-Fi") wifiObserver.observe(n!!) else null
            wifiObservation.value = wifi
            val startKind = if (s.demo) if (demoScenario in setOf(0, 1, 3, 4, 5, 6)) "wifi" else "cellular" else kind(n)
            val permit = manualPreview.value
            var session = if (s.demo) NetworkSession("demo", startKind, true, demo.current, wifi, permit) { store.load().demo }
                else NetworkSession(networkKey(n)!!, startKind, false, wifi = wifi, manualPermit = permit) {
                    physical() == n && !store.load().demo && (startKind != "Wi-Fi" || wifiObserver.stillMatches(wifi)) &&
                        (!enhanced || RuntimePolicy.enabled(store.load()))
                }
            if (!manual) {
                // Events already wait in WorkManager. Only wait the remaining stable interval,
                // or a new interval when this process has no event history yet.
                if (!s.demo && networkId != session.key) {
                    stability.changed(session.key, SystemClock.elapsedRealtime()); networkId = session.key
                }
                delay(if (s.demo) 0 else stability.remaining(SystemClock.elapsedRealtime()).coerceAtMost(policy.debounceMs))
                if (!session.stillCurrent()) return "NETWORK_CHANGED"
            }
            if (fallback && !manual && !observeOnly && !s.demo) {
                // Same network as last time: compare the exit locally and leave Po0 alone unless it changed.
                if (LocalCheck.fallbackProbe(store.load(), session.key, System.currentTimeMillis(), policy)) updateDomestic(n!!)
                if (LocalCheck.canSkipRemote(store.load(), store.load().domesticExit, session.key, System.currentTimeMillis(), policy))
                    return "LOCAL_UNCHANGED"
            }
            val platform = if (s.demo) demo else Po0Platform(vault::read, NetworkTransport(n!!))
            // Slot writes require a recent observation on this exact Android network.
            // Never turn the domestic result into the API source IP; compare both in SlotSync.
            if ((!observeOnly || previewSlot != null) && !s.demo && (s.mode == Mode.AUTO || permit != null || previewSlot != null) && s.layout != null && !s.paused && !s.authBlocked &&
                (System.currentTimeMillis() >= s.nextAllowed || (manual && s.status != "HTTP_429")) && session.stillCurrent()) {
                val d = store.load().domesticExit
                val freshProbe = d != null && d.networkKey == session.key && System.currentTimeMillis() - d.time < policy.freshnessMs
                if (!freshProbe && LocalCheck.probeAllowed(store.load(), session.key, System.currentTimeMillis())) updateDomestic(n!!)
                val observed = store.load().domesticExit
                session = session.copy(observedCidr = observed?.takeIf {
                    it.networkKey == session.key && System.currentTimeMillis() - it.time < policy.freshnessMs &&
                        store.load().probeStatus == "PROBE_OBSERVED"
                }?.cidr)
                val candidate = session.observedCidr
                session = session.copy(revalidate = {
                    val latest = if (startKind == "Wi-Fi") wifiObserver.observe(n!!) else null
                    val identityOk = startKind != "Wi-Fi" || (wifi == null && latest == null) || wifi?.sameIdentity(latest) == true
                    val reply = NetworkTransport(n!!).execute("GET", ProbeSource.IP3322.url)
                    val exit = DomesticProbe.parse(reply, System.currentTimeMillis(), networkKey(n)!!, ProbeSource.IP3322)
                    identityOk && physical() == n && exit.cidr == candidate
                })
            }
            val code = withContext(Dispatchers.IO) { engine.check(platform, session, manual, observeOnly) }
            if (!observeOnly) manualPreview.value = null
            val after = store.load()
            previewSlot?.let { number ->
                val slot = after.layout?.slots?.find { it.number == number }
                if (code in setOf("PRESENT_CURRENT_CHECK", "OBSERVED_MISSING") && !after.paused &&
                    slot?.purpose == SlotPurpose.FIXED && slot.writer == Writer.LOCAL && slot.authorized &&
                    session.observedCidr == after.snapshot?.current && session.stillCurrent()) {
                    manualPreview.value = ManualPermit(after.accountContext, after.layout!!.version, number,
                        session.key, session.wifi, after.snapshot!!, System.currentTimeMillis() + 180_000)
                } else feedback.value = "无法预览：请确认已恢复检查、此槽已授权且出口未变"
            }
            if (!s.demo && n != null && !after.paused && session.stillCurrent() && after.lastCheck > s.lastCheck &&
                System.currentTimeMillis() >= after.nextProbeAllowed &&
                (manual || after.domesticExit?.networkKey != session.key || System.currentTimeMillis() - (after.domesticExit?.time ?: 0) >= policy.cacheMs)) {
                updateDomestic(n)
            }
            feedback.value = statusText(code)
            if (observeOnly && after.mode == Mode.AUTO && after.layout != null && !after.paused && code == "OBSERVED_MISSING" && previewSlot == null)
                scheduleCheck("catch-up")
            if (!manual && SyncPlanning.needsFollowUp(code, store.load())) scheduleCheck("catch-up")
            if (store.load().authBlocked || store.load().paused || store.load().globalBlock != null) refreshSchedule()
            if (code in listOf("HTTP_401", "HTTP_403", "CAPACITY_FULL", "SLOT_CONFLICT", "SLOT_VERIFY_FAILED", "COVERED_OTHER_SLOT", "IDENTITY_AMBIGUOUS", "PENDING_REVIEW")) {
                val latest = store.load()
                val marker = "NOTIFIED:$code:${latest.layout?.version}"
                if (latest.layout != null && marker !in latest.layout!!.notices) {
                    store.save(latest.copy(layout = latest.layout!!.copy(notices = (latest.layout!!.notices + marker).toList().takeLast(100).toSet())))
                    notifyIssue(code)
                }
            }
            return code
        } catch (_: CancellationException) { return "CANCELLED_NETWORK_OR_SETTINGS" }
        catch (_: Exception) { feedback.value = "无法读取本地数据，已停止检查"; return "LOCAL_ERROR" }
        finally {
            if (requestJob === thisRequest) requestJob = null
            busy.value = false; gate.unlock()
            drainPendingCheck()
        }
    }
    private fun edit(scheduleNow: Boolean = false, done: () -> String = { "已保存" }, block: (State) -> State) {
        scope.launch {
            manualPreview.value = null
            operation?.cancelAndJoin()
            requestJob?.cancelAndJoin()
            gate.withLock {
                try { withContext(Dispatchers.IO) { store.save(block(store.load())) }; feedback.value = done() }
                catch (e: IllegalArgumentException) { feedback.value = statusText(e.message ?: "CONFIG_INVALID") }
                catch (_: Exception) { feedback.value = "保存失败" }
            }
            refreshSchedule()
            if (scheduleNow) scheduleCheck("network-check")
        }
    }
    fun pause(value: Boolean) = edit(scheduleNow = !value) { it.copy(paused = value) }
    fun runtimeMode(value: RuntimeMode) = edit { it.copy(runtimeMode = value) }
    fun deviceName(value: String) = edit { it.copy(deviceName = value.trim().take(24)) }
    /** edit() re-arms the alarm and the periodic work with the new interval. */
    fun fallbackMinutes(value: Int) = edit { it.copy(fallbackMinutes = FallbackInterval.clamp(value)) }
    /** Labels the slots another device manages; local authority and Po0 are untouched. */
    fun importPeers(peer: PeerLayout) {
        var count = 0
        edit(done = { if (count == 0) "没有需要更新的槽位" else "已标注 $count 个槽位" }) {
            PeerImport.apply(it, peer).also { r -> count = r.changed.size }.state
        }
    }
    fun exportText(): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        return RedactedExport.build(store.load(), "android", version, System.currentTimeMillis(), lifecycle = LifeLog.recent(600))
    }
    fun refreshRuntime() { scope.launch { runtime.refresh(store.load()) } }
    fun mode(mode: Mode) = edit(scheduleNow = true) { it.copy(mode = mode, lastSuccess = 0) }
    fun configureSlot(slot: ManagedSlot, acknowledged: Boolean) = edit(scheduleNow = true) {
        LayoutRules.saveSlot(it, slot, acknowledged, System.currentTimeMillis())
    }
    // Binding changes what Po0 should hold: drop the local-comparison shortcut and check right away.
    fun bindWifi(slot: Int, name: String, addAp: Boolean, expected: WifiObservation) = edit(scheduleNow = true) {
        val actual = if (it.demo) demoWifi() else wifiObserver.latest
        require(expected.sameIdentity(actual) && (it.demo || wifiObserver.stillMatches(expected))) { "WIFI_UNAVAILABLE" }
        LayoutRules.bind(it, slot, expected, System.currentTimeMillis(), name, addAp).copy(lastSuccess = 0)
    }
    fun revokeWifi(slot: Int) = edit(scheduleNow = true) { LayoutRules.revoke(it, slot).copy(lastSuccess = 0) }
    fun foreground() {
        LifeLog.add("OPEN")
        updater.check(manual = false) // at most once a day, nothing downloaded without a tap
        refreshSchedule()
        wifiObserver.start { changed() }
        manualPreview.value = null
        changed()
        if (!store.load().paused) scheduleCheck("catch-up")
    }
    fun refreshWifi() { scope.launch {
        val n = physical()
        wifiObservation.value = if (store.load().demo) demoWifi() else n?.let { wifiObserver.observe(it) }
    } }
    fun previewManual(number: Int) {
        if (busy.value) return
        operation = scope.launch {
            manualPreview.value = null
            previewSlot = number
            try { runCheck(true, observeOnly = true) } finally { previewSlot = null }
        }
    }
    fun cancelManual() { manualPreview.value = null; operation?.cancel() }
    fun confirmManual() {
        val permit = manualPreview.value ?: return
        if (operation?.isActive == true) return
        operation = scope.launch {
            // Global limiter applies to preview and confirmation; permission expires rather than replaying offline.
            val wait = (store.load().nextAllowed - System.currentTimeMillis()).coerceAtLeast(0)
            feedback.value = "已确认，即将更新"
            busy.value = true
            try { delay(wait); if (manualPreview.value == permit) runCheck(true) }
            finally { busy.value = false; manualPreview.value = null }
        }
    }
    fun demo(enabled: Boolean) = edit {
        // Keep real and simulated credentials separate; discard network/account associations on switching.
        demo = SlotDemoPlatform()
        val base = State(demo = enabled, paused = true, status = if (enabled) "DEMO_READY" else "NOT_CHECKED",
            nextAllowed = it.nextAllowed, authBlocked = it.authBlocked, nextProbeAllowed = it.nextProbeAllowed, deviceName = it.deviceName,
            fallbackMinutes = it.fallbackMinutes)
        if (enabled) demoState(base) else base
    }
    fun saveToken(value: String) = edit {
        val sameAccount = vault.read() == value.trim()
        if (!sameAccount) store.save(State(paused = true, nextAllowed = it.nextAllowed, nextProbeAllowed = it.nextProbeAllowed, deviceName = it.deviceName,
            fallbackMinutes = it.fallbackMinutes))
        vault.save(value.trim())
        credentialPresent.value = true
        it.copy(paused = true, authBlocked = false, lastSuccess = 0, snapshot = null, ownership = emptyList(), profiles = emptyList(), activeProfileId = null,
            slotPlan = if (sameAccount) it.slotPlan else null, layout = if (sameAccount) it.layout else null,
            accountContext = if (sameAccount) it.accountContext else UUID.randomUUID().toString(),
            globalBlock = if (sameAccount) it.globalBlock else null, budget = Budget(), status = "TOKEN_SAVED")
    }
    fun clearToken() = edit { val empty = State(paused = true, nextAllowed = it.nextAllowed, nextProbeAllowed = it.nextProbeAllowed, status = "NO_TOKEN", deviceName = it.deviceName, fallbackMinutes = it.fallbackMinutes); store.save(empty); vault.clear(); credentialPresent.value = false; empty }
    fun clearHistory() = edit { it.copy(events = emptyList(), observations = emptyList(), domesticExit = null, probeStatus = "NOT_CHECKED") }
    private fun demoWifi(): WifiObservation? = when (demoScenario) {
        0, 1 -> WifiObservation("demo", "Example Home", "02:11:22:33:44:01", WifiSecurity.WPA2, System.currentTimeMillis())
        3 -> WifiObservation("demo", "Example Office", "02:11:22:33:44:02", WifiSecurity.WPA3, System.currentTimeMillis())
        4 -> WifiObservation("demo", "Example Home", "02:11:22:33:44:99", WifiSecurity.WPA2, System.currentTimeMillis())
        5 -> WifiObservation("demo", null, null, WifiSecurity.UNKNOWN, System.currentTimeMillis(), false)
        6 -> WifiObservation("demo", "Example Home", "02:11:22:33:44:01", WifiSecurity.WPA2, System.currentTimeMillis())
        else -> null
    }
    private fun demoState(base: State): State {
        demoScenario = 0
        demo.entries = listOf(Entry(Cidr("192.0.2.0/24"), 0), Entry(Cidr("198.51.100.0/24"), 1),
            Entry(Cidr("203.0.112.0/24"), 2), Entry(Cidr("203.0.114.0/24"), 3), Entry(Cidr("198.51.99.0/24")))
        val layout = SlotLayout(slots = listOf(
            ManagedSlot(0, "示例住宅", SlotPurpose.FIXED, Writer.LOCAL, "demo-home", true, authorized = true, baseline = demo.entries[0].cidr),
            ManagedSlot(1, "示例办公室", SlotPurpose.FIXED, Writer.LOCAL, "demo-office", true, authorized = true, baseline = demo.entries[1].cidr),
            ManagedSlot(2, "这台手机", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, authorized = true, baseline = demo.entries[2].cidr),
            ManagedSlot(3, "另一台手机", SlotPurpose.MOBILE, Writer.OTHER_DEVICE), ManagedSlot(4, "保留用途")
        ), identities = listOf(
            NetworkIdentity("demo-home", "示例住宅", "Example Home", setOf(AuthorizedAp("02:11:22:33:44:01", WifiSecurity.WPA2))),
            NetworkIdentity("demo-office", "示例办公室", "Example Office", setOf(AuthorizedAp("02:11:22:33:44:02", WifiSecurity.WPA3)))))
        wifiObservation.value = demoWifi()
        return base.copy(layout = layout, snapshot = Snapshot(demo.current, demo.entries, 5, demo.revision.toString()), lastCheck = System.currentTimeMillis())
    }
    fun nextDemoNetwork() = edit {
        demoScenario = (demoScenario + 1) % 7
        demo.current = Cidr(listOf("203.0.113.0/24", "192.0.3.0/24", "198.51.101.0/24", "203.0.115.0/24", "192.0.4.0/24", "192.0.5.0/24", "203.0.116.0/24")[demoScenario])
        if (demoScenario == 6) demo.entries = demo.entries.map { e -> if (e.slot == 0) Entry(Cidr("198.51.98.0/24"), 0) else e }
        wifiObservation.value = demoWifi()
        it.copy(lastSuccess = 0, status = "DEMO_SCENARIO_$demoScenario")
    }
    private fun notifyIssue(code: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("attention", "需要处理", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, NotificationCompat.Builder(context, "attention").setSmallIcon(R.drawable.ic_stat_fuckpo0jixian)
            .setContentTitle("白名单需要处理").setContentText(statusText(code)).setContentIntent(intent).setAutoCancel(true).build())
    }
}
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as FuckPo0JiXianApp).controller.runCheck(false, fallback = inputData.getString(TRIGGER) == FALLBACK)
        return Result.success() // Engine owns backoff; no second retry scheduler.
    }
    /** Expedited work runs as a short foreground service before Android 12. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("runtime", "短时后台检查", NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(3, NotificationCompat.Builder(applicationContext, "runtime").setSmallIcon(R.drawable.ic_stat_fuckpo0jixian)
            .setContentTitle("去他妈的鸡险正在检查").setContentText("完成后自动退出").build())
    }
    companion object { const val TRIGGER = "trigger"; const val FALLBACK = "fallback" }
}
