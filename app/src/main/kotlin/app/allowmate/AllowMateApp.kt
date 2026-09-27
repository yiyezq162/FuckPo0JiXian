package app.allowmate

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
import androidx.work.*
import app.allowmate.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

class AllowMateApp : Application() {
    lateinit var controller: Controller
    override fun onCreate() { super.onCreate(); controller = Controller(this); controller.start() }
}
class Controller(private val context: Context) {
    val store = LocalStore(context)
    val vault = TokenVault(context)
    val runtime = RuntimeBridge(context)
    val credentialPresent = MutableStateFlow(vault.exists())
    val policy = Policy()
    val engine = Engine(store, policy = policy)
    val busy = MutableStateFlow(false)
    val feedback = MutableStateFlow("")
    val networkLabel = MutableStateFlow("网络信息不可用")
    val currentNetworkKey = MutableStateFlow<String?>(null)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    // Android net IDs may be reused after reboot. Never reuse a prior boot's success cache.
    private val bootEpoch = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }
        .getOrElse { UUID.randomUUID().toString() }
    private fun networkKey(network: Network?) = network?.let { "$bootEpoch:${it.networkHandle}" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private var operation: Job? = null
    private var pendingBackground = false
    @Volatile private var requestJob: Job? = null
    private var networkId: String? = null
    private val stability = Debouncer(policy.debounceMs)
    private var demo = DemoPlatform()

    fun start() {
        scope.launch {
            store.flow.map { Triple(it.runtimeMode, it.paused, RuntimePolicy.enabled(it)) }.distinctUntilChanged()
                .collect { runCatching { runtime.publish(store.load()) }.onFailure { runtime.status.value = "模块状态保存失败 · 已降级" } }
        }
        if (store.load().demo) restoreDemo()
        refreshSchedule()
        try { cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
        }) } catch (_: Exception) { feedback.value = "网络监听不可用；可手动检查" }
    }
    private fun restoreDemo() {
        demo = DemoPlatform()
        store.load().snapshot?.let { demo.current = it.current; demo.capacity = it.capacity; demo.entries = it.entries; demo.revision = it.revision?.toIntOrNull() ?: 0 }
    }
    private fun physical(): Network? = cm.activeNetwork?.takeIf { n ->
        cm.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
    }
    private fun kind(n: Network?): String = cm.getNetworkCapabilities(n)?.let {
        when { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN（不作为直连证明）"
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据（SIM 未采集）"
            else -> "其他网络" }
    } ?: "离线或网络不可用"
    private fun changed() { scope.launch {
        val n = physical(); networkLabel.value = kind(cm.activeNetwork)
        currentNetworkKey.value = networkKey(cm.activeNetwork)
        val id = networkKey(n)
        if (!stability.changed(id, SystemClock.elapsedRealtime())) return@launch
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
    private fun scheduleCheck(name: String, minimumDelay: Long = 0) {
        if (!vault.exists() || physical() == null) return
        val planned = SyncPlanning.delayMs(store.load(), System.currentTimeMillis(), stability.remaining(SystemClock.elapsedRealtime())) ?: return
        val wait = maxOf(planned, minimumDelay)
        feedback.value = if (wait > policy.debounceMs) "网络已变化，等待限频结束后自动检查" else "等待网络稳定后检查"
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CheckWorker>().setInitialDelay(wait, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    private fun refreshSchedule() {
        val wm = WorkManager.getInstance(context)
        if (store.load().paused || store.load().demo || store.load().authBlocked || !vault.exists()) {
            wm.cancelUniqueWork("fallback"); wm.cancelUniqueWork("network-check"); wm.cancelUniqueWork("catch-up")
        } else wm.enqueueUniquePeriodicWork("fallback", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(policy.fallbackMinutes, TimeUnit.MINUTES)
                .setInitialDelay(policy.fallbackMinutes, TimeUnit.MINUTES)
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
                if (state.demo || state.paused) { feedback.value = "请在真实环境恢复检查后再探测国内出口"; return@launch }
                if (now < state.nextProbeAllowed) { feedback.value = "国内出口检查仍在限频期"; return@launch }
                val network = cm.activeNetwork
                if (network == null || cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) {
                    feedback.value = "没有可用网络，未发起出口探测"; return@launch
                }
                feedback.value = if (updateDomestic(network)) "国内出口已观察" else "国内出口未验证，保留上次观察"
            } catch (_: CancellationException) {
                feedback.value = "国内出口检查已取消"
            } catch (_: Exception) {
                feedback.value = "国内出口状态无法保存，检查已停止"
            } finally {
                if (requestJob === thisRequest) requestJob = null
                busy.value = false; gate.unlock()
                drainPendingCheck()
            }
        }
    }
    private suspend fun updateDomestic(network: Network): Boolean {
        val now = System.currentTimeMillis()
        val state = store.load().copy(nextProbeAllowed = now + policy.minIntervalMs, probeStatus = "PROBE_RUNNING")
        withContext(Dispatchers.IO) { store.save(state) }
        return try {
            val source = ProbeSource.IP3322
            val response = withContext(Dispatchers.IO) { NetworkTransport(network).execute("GET", source.url) }
            if (cm.activeNetwork != network) throw ApiFailure("NETWORK_CHANGED")
            val observed = DomesticProbe.parse(response, now, networkKey(network)!!, source)
            withContext(Dispatchers.IO) { store.save(state.copy(domesticExit = observed, probeStatus = "PROBE_OBSERVED")) }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { store.save(state.copy(probeStatus = "NETWORK_CHANGED")) }
            throw e
        } catch (e: Exception) {
            val wait = maxOf(policy.minIntervalMs, (e as? ApiFailure)?.retryAfterMs ?: 0)
            withContext(Dispatchers.IO) {
                store.save(state.copy(probeStatus = (e as? ApiFailure)?.code ?: "PROBE_FAILED",
                    nextProbeAllowed = if (Long.MAX_VALUE - now < wait) Long.MAX_VALUE else now + wait))
            }
            false
        }
    }
    suspend fun runCheck(manual: Boolean, observeOnly: Boolean = false, enhanced: Boolean = false): String = withContext(Dispatchers.Main.immediate) {
        val began = SystemClock.elapsedRealtime()
        val requests = NetworkTransport.requests.get()
        val selected = store.load().runtimeMode.name
        val result = runCheckOnMain(manual, observeOnly, enhanced)
        // Bounded, credential-free evidence for standard/enhanced comparisons. BUSY
        // did not own the gate and must not count another operation's HTTP attempts.
        val count = if (result == "BUSY") 0 else NetworkTransport.requests.get() - requests
        val safeCode = result.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "UNKNOWN"
        val path = if (enhanced) "enhanced" else if (manual) "manual" else "workmanager"
        android.util.Log.i("AllowMateCheck", "uid=${android.os.Process.myUid()} mode=$selected path=$path " +
            "beginMs=$began endMs=${SystemClock.elapsedRealtime()} httpAttempts=$count result=$safeCode")
        result
    }
    private suspend fun runCheckOnMain(manual: Boolean, observeOnly: Boolean, enhanced: Boolean): String {
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
            if (!s.demo && n == null) { feedback.value = "没有可绑定的已验证底层网络；VPN 不等于直连"; return "NO_NETWORK" }
            var session = if (s.demo) NetworkSession("demo", "模拟网络", true) { store.load().demo }
                else NetworkSession(networkKey(n)!!, kind(n), false) {
                    physical() == n && !store.load().demo && (!enhanced || RuntimePolicy.enabled(store.load()))
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
            val platform = if (s.demo) demo else Po0Platform(vault::read, NetworkTransport(n!!))
            // Slot writes require a recent observation on this exact Android network.
            // Never turn the domestic result into the API source IP; compare both in SlotSync.
            if (!observeOnly && !s.demo && s.mode == Mode.AUTO && s.slotPlan != null && !s.paused && !s.authBlocked &&
                System.currentTimeMillis() >= s.nextAllowed && session.stillCurrent()) {
                val d = store.load().domesticExit
                val freshProbe = d != null && d.networkKey == session.key && System.currentTimeMillis() - d.time < policy.minIntervalMs
                if (!freshProbe && System.currentTimeMillis() >= store.load().nextProbeAllowed) updateDomestic(n!!)
                val observed = store.load().domesticExit
                session = session.copy(observedCidr = observed?.takeIf {
                    it.networkKey == session.key && System.currentTimeMillis() - it.time < policy.minIntervalMs &&
                        store.load().probeStatus == "PROBE_OBSERVED"
                }?.cidr)
            }
            val code = withContext(Dispatchers.IO) { engine.check(platform, session, manual, observeOnly) }
            val after = store.load()
            if (!s.demo && n != null && !after.paused && session.stillCurrent() && after.lastCheck > s.lastCheck &&
                System.currentTimeMillis() >= after.nextProbeAllowed &&
                (manual || after.domesticExit?.networkKey != session.key || System.currentTimeMillis() - (after.domesticExit?.time ?: 0) >= policy.cacheMs)) {
                updateDomestic(n)
            }
            feedback.value = statusText(code)
            if (observeOnly && after.mode == Mode.AUTO && after.slotPlan != null && !after.paused && code == "OBSERVED_MISSING")
                scheduleCheck("catch-up")
            if (!manual && SyncPlanning.needsFollowUp(code, store.load())) scheduleCheck("catch-up")
            if (store.load().authBlocked || store.load().paused) refreshSchedule()
            if (code in listOf("HTTP_401", "HTTP_403", "CAPACITY_FULL", "FIXED_DRIFT", "VERIFY_FAILED", "HOME_GUARD_FAILED", "SLOT_CONFLICT", "SLOT_VERIFY_FAILED")) notifyIssue(code)
            return code
        } catch (_: CancellationException) { return "CANCELLED_NETWORK_OR_SETTINGS" }
        catch (_: Exception) { feedback.value = "本地存储或凭据不可用；已停止本次检查"; return "LOCAL_ERROR" }
        finally {
            if (requestJob === thisRequest) requestJob = null
            busy.value = false; gate.unlock()
            drainPendingCheck()
        }
    }
    private fun edit(scheduleNow: Boolean = false, block: (State) -> State) {
        scope.launch {
            operation?.cancelAndJoin()
            requestJob?.cancelAndJoin()
            gate.withLock {
                try { withContext(Dispatchers.IO) { store.save(block(store.load())) }; feedback.value = "已保存到本机" }
                catch (e: IllegalArgumentException) { feedback.value = "无法保存：请检查预算、保护状态或关联条目" }
                catch (_: Exception) { feedback.value = "保存失败；请检查本机存储" }
            }
            refreshSchedule()
            if (scheduleNow) scheduleCheck("network-check")
        }
    }
    fun pause(value: Boolean) = edit(scheduleNow = !value) { it.copy(paused = value) }
    fun runtimeMode(value: RuntimeMode) = edit { it.copy(runtimeMode = value) }
    fun refreshRuntime() { scope.launch { runtime.refresh(store.load()) } }
    fun mode(mode: Mode) = edit(scheduleNow = true) { it.copy(mode = mode, lastSuccess = 0) }
    fun configurePo0(plan: SlotPlan) = edit {
        val checked = SlotConfiguration.prepare(it, plan.home, plan.homeSlot, plan.mobileSlot)
        it.copy(slotPlan = checked, paused = true, lastSuccess = 0, status = "SLOT_CONFIGURED")
    }
    fun demo(enabled: Boolean) = edit {
        // Keep real and simulated credentials separate; discard network/account associations on switching.
        demo = DemoPlatform()
        State(demo = enabled, paused = true, status = if (enabled) "DEMO_READY" else "NOT_CHECKED",
            nextAllowed = it.nextAllowed, authBlocked = it.authBlocked, nextProbeAllowed = it.nextProbeAllowed)
    }
    fun saveToken(value: String) = edit {
        val sameAccount = vault.read() == value.trim()
        vault.save(value.trim())
        credentialPresent.value = true
        it.copy(paused = true, authBlocked = false, lastSuccess = 0, snapshot = null, ownership = emptyList(), profiles = emptyList(), activeProfileId = null,
            slotPlan = if (sameAccount) it.slotPlan else null, budget = Budget(), status = "TOKEN_SAVED")
    }
    fun clearToken() = edit { vault.clear(); credentialPresent.value = false; it.copy(paused = true, snapshot = null, ownership = emptyList(), profiles = emptyList(), activeProfileId = null, slotPlan = null, budget = Budget(), status = "NO_TOKEN") }
    fun budget(fixed: Int, mobile: Int) = edit {
        val b = Budget(fixed, mobile)
        require(it.snapshot?.let { snap -> Allocation.budgetFits(it, snap, b) } ?: (fixed == 0 && mobile == 0))
        it.copy(budget = b)
    }
    fun addProfile(name: String, kind: Kind) = edit {
        require(name.isNotBlank() && name.length <= 40)
        require(it.profiles.count { p -> p.kind == kind } < if (kind == Kind.FIXED) it.budget.fixed else it.budget.mobile)
        it.copy(profiles = it.profiles + Profile(UUID.randomUUID().toString(), name.trim(), kind))
    }
    fun removeProfile(id: String) = edit { it.copy(profiles = it.profiles.filterNot { p -> p.id == id }, activeProfileId = it.activeProfileId.takeUnless { active -> active == id }) }
    fun selectProfile(id: String?) = edit {
        require(id == null || it.profiles.any { p -> p.id == id })
        it.copy(activeProfileId = id, lastSuccess = 0)
    }
    fun associate(id: String, confirmed: Boolean) = edit {
        Allocation.associate(it, id, requireNotNull(it.snapshot).current, it.networkKey, confirmed)
    }
    fun claim(cidr: Cidr) = edit { Allocation.claim(it, cidr, true) }
    fun protection(cidr: Cidr, protect: Boolean) = edit {
        require(it.ownership.any { o -> o.cidr == cidr && o.authorized })
        require(protect || it.profiles.none { p -> p.kind == Kind.FIXED && p.cidr == cidr })
        it.copy(ownership = it.ownership.map { o -> if (o.cidr == cidr) o.copy(protected = protect) else o })
    }
    fun clearHistory() = edit { it.copy(events = emptyList(), observations = emptyList(), domesticExit = null, probeStatus = "NOT_CHECKED") }
    fun nextDemoNetwork() = edit { demo.current = Cidr(if (demo.current.value == "203.0.113.0/24") "192.0.2.0/24" else "203.0.113.0/24"); it.copy(lastSuccess = 0) }
    private fun notifyIssue(code: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("attention", "需要处理", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, NotificationCompat.Builder(context, "attention").setSmallIcon(R.drawable.ic_allowmate)
            .setContentTitle("白名单随行需要处理").setContentText(statusText(code)).setContentIntent(intent).setAutoCancel(true).build())
    }
}
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as AllowMateApp).controller.runCheck(false)
        return Result.success() // Engine owns backoff; no second retry scheduler.
    }
}
