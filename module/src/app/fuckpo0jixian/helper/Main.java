package app.fuckpo0jixian.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Root-only event helper. No internet request, credential read, shell interpolation or business state. It watches the
 * physical network (underneath any VPN), the home router's WAN address (LAN only, see [Gateway]) and long screen-off
 * periods, wakes the APK's fixed check service, and keeps a short in-memory timeline the APK reads for its debug log.
 */
public final class Main {
    private static final String PKG = "app.fuckpo0jixian";
    private static final File DIR = new File("/data/adb/modules/fuckpo0jixian_helper");
    private static final String SOCKET = "fuckpo0jixian.runtime.v1";
    /** Exit code for "the system restarted under us": service.sh waits for the new boot and starts a fresh helper. */
    private static final int SYSTEM_RESTARTED = 3;
    private static String epoch = UUID.randomUUID().toString();
    private static long packageStamp;
    private static final long startedMs = SystemClock.elapsedRealtime();
    private static int bootCount = -1;
    private static int bootCount() {
        if (bootCount >= 0) return bootCount;
        // systemContext's attributed package belongs to UID1000, not this root
        // process. Some ROMs reject ContentResolver calls on that identity.
        // Fixed read-only system CLI, bounded; never user-controlled shell input.
        try {
            java.lang.Process read = new ProcessBuilder("/system/bin/settings", "get", "global", "boot_count")
                .redirectError(new File("/dev/null")).start();
            if (!read.waitFor(2, TimeUnit.SECONDS)) { read.destroy(); return -1; }
            if (read.exitValue() == 0) {
                try (BufferedReader line = new BufferedReader(new InputStreamReader(read.getInputStream()))) {
                    int value = Integer.parseInt(line.readLine());
                    if (value >= 0) bootCount = value;
                }
            }
        } catch (Exception ignored) { }
        return bootCount;
    }
    private static Context context;
    private static Handler handler;
    private static String certificate, instance = "", generation = "", ticket = "", lastStatus = "STARTING";
    private static long issued, lastWake;
    private static boolean wakeFailed;
    private static String recoveryEpoch = "";
    private static FileObserver moduleObserver, configObserver;
    private static final Runnable wake = () -> trigger("network");
    private static final Runnable screenWake = () -> trigger("screen");
    private static final Runnable gatewayWake = () -> trigger("gateway");
    /** Screen off at least this long: the exit may well have moved meanwhile (a cheap local comparison follows). */
    private static final long SCREEN_OFF_MS = 10 * 60_000;
    private static long screenOffAt;
    private static String versionName = "";
    private static Gateway gateway;
    private static long keepAliveAt;
    private static boolean keepAliveWanted = true;
    private static ConnectivityManager cm;
    private static final java.util.Set<Network> physical = new java.util.HashSet<>();
    private static String observed = "", primaryKind = "none";
    private static Stats stats;
    /** The last ticket the APK claimed, and whether the APK was running before that wake; its RESULT is counted once. */
    private static String claimed = "";
    private static boolean pendingRevived, ticketRevived;

    // In-memory timeline for the APK's debug log: never written to disk, gone with the process.
    private static final java.util.ArrayDeque<String> timeline = new java.util.ArrayDeque<>();
    private static long sequence;
    static void note(String kind, String detail) {
        synchronized (timeline) {
            timeline.addLast(++sequence + " " + System.currentTimeMillis() + " " + kind + " " + detail.replace('\n', ' '));
            while (timeline.size() > 300) timeline.removeFirst();
        }
    }
    /** Lines after [since], oldest first, within one IPC reply. */
    private static String events(long since) throws Exception {
        org.json.JSONArray lines = new org.json.JSONArray();
        long latest;
        synchronized (timeline) {
            latest = sequence;
            int size = 0;
            java.util.List<String> newer = new java.util.ArrayList<>();
            java.util.Iterator<String> it = timeline.descendingIterator();
            while (it.hasNext()) {
                String line = it.next();
                if (Long.parseLong(line.substring(0, line.indexOf(' '))) <= since || (size += line.length()) > 24_000) break;
                newer.add(0, line);
            }
            for (String line : newer) lines.put(line);
        }
        return new JSONObject().put("seq", latest).put("lines", lines).toString();
    }
    // Fallback cadence follows the APK's own setting (2–59 minutes, published in runtime-v1.json; 10 when absent):
    // on each tick the APK compares its exit locally and only asks Po0 on change.
    private static volatile long fallbackMs = 10 * 60_000;
    private static final Runnable fallback = new Runnable() { public void run() {
        handler.postDelayed(this, fallbackMs);
        trigger("fallback");
    } };
    /**
     * Oldest APK versionCode this module works with (the one it was packaged with). A newer APK is accepted as long as
     * it speaks the same runtime protocol, so an in-app APK update does not disable the module until it is updated too.
     */
    private static long expectedVersion = -1;
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0 || args.length != 0) return;
        // Kernel releases this lock on exit/crash. Only a helper proven to be from an earlier boot is ever killed (evictStale).
        RandomAccessFile lockFile = new RandomAccessFile(new File(DIR, "helper.lock"), "rw");
        FileLock lock = lockFile.getChannel().tryLock();
        if (lock == null) {
            // Held by a live helper of this boot (a duplicate start: exit quietly), or by one left from before a soft reboot.
            if (!evictStale()) return;
            if ((lock = lockFile.getChannel().tryLock()) == null) System.exit(0);
        }
        certificate = new String(Files.readAllBytes(new File(DIR, "certificate.sha256").toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
        for (String line : Files.readAllLines(new File(DIR, "module.prop").toPath(), java.nio.charset.StandardCharsets.UTF_8))
            if (line.startsWith("versionCode=")) expectedVersion = Long.parseLong(line.substring("versionCode=".length()).trim());
            else if (line.startsWith("version=")) versionName = line.substring("version=".length()).trim();
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        context = (Context) at.getMethod("getSystemContext").invoke(thread);
        // A soft reboot restarts system_server but not the kernel, and this root process survives it with a dead
        // context: it then answered every call with UNAVAILABLE and kept the socket, so the next boot's helper could
        // not start (seen on an OPPO with KernelSU, 4 failed starts). Leave as soon as the system goes away.
        IBinder system = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "activity");
        if (system == null) throw new IllegalStateException("SYSTEM_NOT_READY");
        system.linkToDeath(() -> {
            android.util.Log.w("FuckPo0JiXianHelper", "SYSTEM_RESTARTED");
            Runtime.getRuntime().halt(SYSTEM_RESTARTED);
        }, 0);
        handler = new Handler(Looper.getMainLooper());
        LocalServerSocket server;
        try { server = new LocalServerSocket(SOCKET); }
        catch (IOException taken) {
            Integer theirs = holderBoot();
            if (theirs != null && theirs >= 0 && theirs == bootCount()) System.exit(0); // a live helper of this boot
            if (!evictStale()) throw taken;
            server = new LocalServerSocket(SOCKET);
        }
        final LocalServerSocket listening = server;
        Thread ipc = new Thread(() -> serve(listening), "fuckpo0jixian-ipc");
        ipc.setDaemon(true); ipc.start();
        moduleObserver = new FileObserver(DIR.getAbsolutePath(), FileObserver.CREATE | FileObserver.DELETE | FileObserver.DELETE_SELF | FileObserver.MOVED_TO) {
            public void onEvent(int event, String path) { handler.post(Main::lifecycle); }
        };
        moduleObserver.startWatching();
        stats = Stats.load(); stats.save();
        note("START", "helper " + versionName + " boot=" + bootCount());
        gateway = new Gateway(new Gateway.Listener() {
            public void changed(String wan, String previous, String method) {
                note("GATEWAY", "wan " + previous + " -> " + wan + " via " + method);
                synchronized (Main.class) { stats.gatewayChanges++; stats.save(); }
                handler.post(() -> { handler.removeCallbacks(gatewayWake); handler.post(gatewayWake); });
            }
            public void note(String kind, String detail) { Main.note(kind, detail); }
        });
        cm = context.getSystemService(ConnectivityManager.class);
        try {
            // Default requests exclude VPNs: these are the Wi-Fi and mobile networks underneath any VPN.
            android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                public void onAvailable(Network n) { physical.add(n); evaluate(); }
                public void onLost(Network n) { physical.remove(n); evaluate(); }
                public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) { physical.add(n); evaluate(); }
                // Same network, new local address or IPv6 prefix (mobile re-attach, router redial): the exit likely moved.
                public void onLinkPropertiesChanged(Network n, android.net.LinkProperties link) { physical.add(n); evaluate(); }
            }, handler);
        } catch (Exception denied) {
            // late_start can precede publication of ConnectivityService. A live but
            // permanently unregistered helper would also block the boot-completed hook.
            // Exit so the module supervisor's bounded backoff can create a fresh context.
            android.util.Log.w("FuckPo0JiXianHelper", "CALLBACK_START_FAILED_" + denied.getClass().getSimpleName());
            throw new IllegalStateException("CALLBACK_START_FAILED", denied);
        }
        try {
            android.hardware.display.DisplayManager displays = context.getSystemService(android.hardware.display.DisplayManager.class);
            displays.registerDisplayListener(new android.hardware.display.DisplayManager.DisplayListener() {
                public void onDisplayAdded(int id) { }
                public void onDisplayRemoved(int id) { }
                public void onDisplayChanged(int id) { if (id == android.view.Display.DEFAULT_DISPLAY) screen(displays); }
            }, handler);
            screen(displays);
        } catch (Exception unavailable) { note("SCREEN", "watch unavailable " + unavailable.getClass().getSimpleName()); }
        // Lifecycle watchdog: no HTTP, no network probing, no wake lock.
        handler.post(new Runnable() { public void run() { lifecycle(); handler.postDelayed(this, 60_000); } });
        handler.postDelayed(fallback, fallbackMs);
        Looper.loop();
        lock.release(); lockFile.close();
    }
    /** The boot the helper holding the socket reports, or null when none answers. */
    private static Integer holderBoot() {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(2000);
            if (socket.getPeerCredentials().getUid() != 0) return null;
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeUTF("1"); out.writeUTF("DIAGNOSTICS"); out.writeUTF(""); out.writeUTF(""); out.flush();
            return new JSONObject(new DataInputStream(socket.getInputStream()).readUTF()).optInt("boot", -1);
        } catch (Exception none) { return null; }
    }

    /**
     * Ends a helper left from an earlier boot (it survived a soft reboot, see main), so this one can take over. Only
     * when it answers with another boot than ours, and only processes running this exact helper class.
     */
    private static boolean evictStale() throws InterruptedException {
        int boot = bootCount();
        Integer theirs = holderBoot();
        if (boot < 0 || theirs == null || theirs == boot) return false;
        int self = android.os.Process.myPid(), killed = 0;
        File[] entries = new File("/proc").listFiles();
        if (entries != null) for (File entry : entries) {
            String name = entry.getName();
            if (!name.matches("\\d{1,9}") || Integer.parseInt(name) == self) continue;
            try (FileInputStream in = new FileInputStream(new File(entry, "cmdline"))) {
                byte[] head = new byte[512];
                int n = Math.max(0, in.read(head));
                if (!java.util.Arrays.asList(new String(head, 0, n, java.nio.charset.StandardCharsets.UTF_8).split("\0"))
                    .contains(Main.class.getName())) continue;
                android.os.Process.sendSignal(Integer.parseInt(name), android.os.Process.SIGNAL_KILL);
                killed++;
            } catch (IOException ignored) { }
        }
        if (killed == 0) return false;
        for (int i = 0; i < 15 && holderBoot() != null; i++) Thread.sleep(200);
        note("START", "ended a helper left from boot " + theirs);
        return true;
    }

    /** Picks the network the APK checks from (validated Wi-Fi first) and reacts when it or its addressing moves. */
    private static void evaluate() {
        Network best = null;
        NetworkCapabilities bestCaps = null;
        int rank = -1;
        for (Network n : physical) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            int r = (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 4 : 0) +
                (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND) ? 2 : 0) +
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 0);
            if (r > rank) { rank = r; best = n; bestCaps = caps; }
        }
        android.net.LinkProperties link = best == null ? null : cm.getLinkProperties(best);
        boolean wifi = bestCaps != null && bestCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean validated = bestCaps != null && bestCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        String kind = best == null ? "none" : wifi ? "wifi" : bestCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular" : "other";
        StringBuilder v4 = new StringBuilder(), v6 = new StringBuilder();
        java.net.InetAddress router = null;
        if (link != null) {
            java.util.List<String> four = new java.util.ArrayList<>(), six = new java.util.ArrayList<>();
            for (android.net.LinkAddress a : link.getLinkAddresses()) {
                java.net.InetAddress address = a.getAddress();
                if (address instanceof java.net.Inet4Address) four.add(address.getHostAddress());
                else if ((address.getAddress()[0] & 0xe0) == 0x20) { // global unicast: its /64 prefix
                    byte[] b = address.getAddress();
                    String prefix = String.format("%x:%x:%x:%x::/64", ((b[0] & 255) << 8) | (b[1] & 255), ((b[2] & 255) << 8) | (b[3] & 255),
                        ((b[4] & 255) << 8) | (b[5] & 255), ((b[6] & 255) << 8) | (b[7] & 255));
                    if (!six.contains(prefix)) six.add(prefix);
                }
            }
            java.util.Collections.sort(four); java.util.Collections.sort(six);
            v4.append(String.join(",", four)); v6.append(String.join(",", six));
            for (android.net.RouteInfo route : link.getRoutes())
                if (route.isDefaultRoute() && route.getGateway() instanceof java.net.Inet4Address && !route.getGateway().isAnyLocalAddress())
                    router = route.getGateway();
        }
        gateway.watch(wifi && validated ? best : null, wifi && validated ? router : null);
        String key = best + ":" + kind + ":" + validated + ":" + v4 + ":" + v6;
        if (key.equals(observed)) return;
        // The first evaluation after start is where we are, not a change.
        boolean change = !observed.isEmpty() && best != null && validated;
        observed = key; primaryKind = kind;
        if (change) synchronized (Main.class) { stats.networkChanges++; stats.save(); describe(lastStatus); }
        note("NET", kind + (best == null ? "" : " validated=" + validated + " v4=" + v4 + " v6=" + v6 +
            (router != null ? " gw=" + router.getHostAddress() : "")));
        if (best != null && validated) { handler.removeCallbacks(wake); handler.postDelayed(wake, 500); }
    }

    private static void screen(android.hardware.display.DisplayManager displays) {
        android.view.Display display = displays.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        boolean on = display != null && display.getState() == android.view.Display.STATE_ON;
        long now = SystemClock.elapsedRealtime();
        if (!on) { if (screenOffAt == 0) screenOffAt = now; return; }
        if (screenOffAt == 0) return;
        long off = now - screenOffAt;
        screenOffAt = 0;
        if (off < SCREEN_OFF_MS) return;
        note("SCREEN", "on after " + off / 60_000 + "min off");
        // Give Wi-Fi a moment to reconnect after sleep; a network change meanwhile supersedes this.
        handler.removeCallbacks(screenWake); handler.postDelayed(screenWake, 5_000);
    }

    /**
     * Root keep-alive while enhancement is on: exempt the APK from battery optimization (the same switch as the
     * system dialog), keep its standby bucket active and allow background running. Only ever grants; at most hourly.
     */
    private static void keepAlive() {
        long now = SystemClock.elapsedRealtime();
        if (!keepAliveWanted || (keepAliveAt != 0 && now - keepAliveAt < 3_600_000)) return;
        keepAliveAt = now;
        gateway.handler().post(() -> {
            StringBuilder result = new StringBuilder();
            String[][] commands = {
                {"/system/bin/dumpsys", "deviceidle", "whitelist", "+" + PKG},
                {"/system/bin/am", "set-standby-bucket", PKG, "active"},
                {"/system/bin/cmd", "appops", "set", PKG, "RUN_ANY_IN_BACKGROUND", "allow"}};
            for (String[] command : commands) {
                int code;
                try {
                    java.lang.Process process = new ProcessBuilder(command).redirectOutput(new File("/dev/null"))
                        .redirectError(new File("/dev/null")).start();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); code = -1; } else code = process.exitValue();
                } catch (Exception e) { code = -2; }
                result.append(command[1]).append('=').append(code).append(' ');
            }
            note("KEEPALIVE", result.toString().trim());
        });
    }

    private static synchronized PackageInfo identity() throws Exception {
        PackageInfo p = context.getPackageManager().getPackageInfo(PKG, PackageManager.GET_SIGNING_CERTIFICATES);
        if (p.applicationInfo == null || p.applicationInfo.uid < 10000 || p.applicationInfo.uid >= 100000) throw new SecurityException();
        byte[] bytes = p.signingInfo.getApkContentsSigners()[0].toByteArray();
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", b & 255));
        if (!certificate.equals(digest.toString())) throw new SecurityException();
        if (packageStamp != p.lastUpdateTime) { packageStamp = p.lastUpdateTime; epoch = UUID.randomUUID().toString(); ticket = ""; wakeFailed = false; }
        return p;
    }
    private static synchronized String state() {
        try {
            if (!DIR.isDirectory() || new File(DIR, "disable").exists() || new File(DIR, "remove").exists()) return "DISABLED";
            PackageInfo p = identity();
            if ((p.applicationInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0) return "STOPPED";
            if (expectedVersion < 0 || p.getLongVersionCode() < expectedVersion) return "VERSION";
            if (!context.getSystemService(UserManager.class).isUserUnlocked()) return "LOCKED";
            File config = new File(p.applicationInfo.dataDir, "no_backup/runtime-v1.json");
            if (!config.isFile() || config.length() > 1024) return "STOPPED";
            JSONObject data = new JSONObject(new String(Files.readAllBytes(config.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            if (data.getInt("protocol") != 1) return "VERSION";
            long minutes = Math.max(2, Math.min(59, data.optInt("fallbackMinutes", 10)));
            if (minutes * 60_000 != fallbackMs) {
                fallbackMs = minutes * 60_000;
                handler.removeCallbacks(fallback);
                handler.postDelayed(fallback, fallbackMs);
            }
            String next = data.getString("instance");
            if (!next.matches("[a-f0-9-]{36}")) return "IDENTITY";
            if (!instance.equals(next)) { instance = next; ticket = ""; }
            String nextGeneration = data.getString("generation");
            if (!generation.equals(nextGeneration)) { generation = nextGeneration; epoch = UUID.randomUUID().toString(); ticket = ""; wakeFailed = false; }
            keepAliveWanted = data.optBoolean("keepAlive", true);
            if (!data.getBoolean("enabled")) { ticket = ""; return "STOPPED"; }
            if (lastStatus.equals("CALLBACK_FAILED")) return lastStatus;
            return "READY";
        } catch (Exception e) { return "UNAVAILABLE"; }
    }
    private static synchronized void lifecycle() {
        String s = state();
        if (!s.equals(lastStatus)) note("STATE", lastStatus + " -> " + s);
        if (s.equals("READY")) keepAlive();
        if (!s.equals(lastStatus)) {
            // Best-effort historical evidence only. SIGKILL cannot promise a receipt.
            android.util.AtomicFile report = new android.util.AtomicFile(new File(DIR, "last-report.json"));
            FileOutputStream output = null;
            try {
                output = report.startWrite();
                String text = new JSONObject().put("boot", bootCount())
                    .put("at", System.currentTimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime())
                    .put("status", s).put("disabled", new File(DIR, "disable").exists())
                    .put("pendingRemoval", new File(DIR, "remove").exists()).toString();
                output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); report.finishWrite(output);
            } catch (Exception ignored) { if (output != null) report.failWrite(output); }
        }
        describe(s);
        if (s.equals("DISABLED")) { android.os.Process.killProcess(android.os.Process.myPid()); return; }
        if (configObserver == null && !s.equals("LOCKED")) {
            try {
                File directory = new File(identity().applicationInfo.dataDir, "no_backup");
                configObserver = new FileObserver(directory.getAbsolutePath(), FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE | FileObserver.DELETE) {
                    public void onEvent(int e, String path) { if ("runtime-v1.json".equals(path)) handler.post(Main::lifecycle); }
                };
                configObserver.startWatching();
            } catch (Exception ignored) { }
        }
        // One recovery event per ready generation, not a periodic APK wake. This
        // also covers same-version APK replacement and enabling/resuming without
        // a network change. state() still refuses locked or force-stopped apps.
        if (s.equals("READY") && !epoch.equals(recoveryEpoch)) {
            recoveryEpoch = epoch;
            handler.removeCallbacks(wake);
            handler.post(wake);
        }
        if (!lastStatus.equals("CALLBACK_FAILED")) lastStatus = s;
    }
    private static synchronized void trigger(String kind) {
        String current = state();
        if (!current.equals("READY")) { note("WAKE", kind + " skipped " + current); return; }
        long now = SystemClock.elapsedRealtime();
        // Event storm bound only; APK owns business debounce/rate/backoff. A fallback never delays a network wake.
        if (lastWake != 0 && now - lastWake < 3_000) {
            if (!kind.equals("fallback")) {
                Runnable again = kind.equals("gateway") ? gatewayWake : kind.equals("screen") ? screenWake : wake;
                handler.removeCallbacks(again); handler.postDelayed(again, 3_000 - (now - lastWake));
            }
            return;
        }
        lastWake = now; issued = now; ticket = UUID.randomUUID().toString();
        pendingRevived = !appRunning();
        final String pending = ticket;
        // Some OEMs reject an otherwise valid start while am still exits normally.
        // Only the APK claiming this one-use ticket confirms entry. This one-shot
        // timer is not a poll, wake lock, or proof of an HTTP request completing.
        handler.postDelayed(() -> {
            synchronized (Main.class) {
                if (state().equals("READY") && pending.equals(ticket)) {
                    wakeFailed = true; ticket = "";
                    android.util.Log.w("FuckPo0JiXianHelper", "WAKE_UNCONFIRMED");
                    note("WAKE", "unconfirmed after 30s");
                }
            }
        }, 30_000);
        try {
            java.lang.Process process = new ProcessBuilder("/system/bin/am", "start-foreground-service", "--user", "0",
                "-n", "app.fuckpo0jixian/.RuntimeSyncService", "-a", "app.fuckpo0jixian.RUNTIME_CHECK", "-f", "0x10", "--es", "ticket", ticket,
                "--es", "trigger", kind)
                .redirectOutput(new File("/dev/null")).redirectError(new File("/dev/null")).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); wakeFailed = true; note("WAKE", kind + " am timeout"); }
            else if (process.exitValue() != 0) { wakeFailed = true; note("WAKE", kind + " am exit=" + process.exitValue()); }
            else note("WAKE", kind + " sent");
        } catch (Exception e) { wakeFailed = true; note("WAKE", kind + " failed " + e.getClass().getSimpleName()); }
    }
    /** The manager's module list shows this; READY with a failed wake reads as a warning. */
    private static void describe(String status) {
        if (stats == null) return;
        Stats.describe(DIR, stats.description(status.equals("READY") && wakeFailed ? "START_FAILED" : status));
    }

    /** Whether the APK's main process exists right now (root reads every process's command line). */
    private static boolean appRunning() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return true; // Unknown: never claim a revival we did not see.
        byte[] want = PKG.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (File entry : entries) {
            String name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            try (FileInputStream in = new FileInputStream(new File(entry, "cmdline"))) {
                byte[] head = new byte[want.length + 1];
                int n = in.read(head);
                if (n >= want.length && (n == want.length || head[want.length] == 0) &&
                    java.util.Arrays.equals(java.util.Arrays.copyOf(head, want.length), want)) return true;
            } catch (IOException ignored) { }
        }
        return false;
    }

    private static void serve(LocalServerSocket server) {
        while (true) try (LocalSocket socket = server.accept()) {
            socket.setSoTimeout(1500);
            int caller = socket.getPeerCredentials().getUid();
            if (caller != 0 && caller != identity().applicationInfo.uid) {
                android.util.Log.w("FuckPo0JiXianHelper", "IPC_UID_REJECTED"); continue;
            }
            DataInputStream input = new DataInputStream(socket.getInputStream());
            String protocol = input.readUTF(), action = input.readUTF(), client = input.readUTF(), claim = input.readUTF();
            String reply;
            synchronized (Main.class) {
                String s = state();
                if (!protocol.equals("1")) reply = "VERSION";
                else if (caller == 0 && !action.equals("DIAGNOSTICS")) reply = "REJECTED";
                else if (action.equals("INFO") || (caller == 0 && action.equals("DIAGNOSTICS"))) {
                    int boot = bootCount();
                    reply = new JSONObject().put("boot", boot).put("startedMs", startedMs)
                        .put("status", s.equals("READY") ? (wakeFailed ? "START_FAILED" : "READY:" + epoch) : s)
                        .put("version", expectedVersion).put("versionName", versionName)
                        .put("network", primaryKind).put("gateway", gateway.status()).put("wan", gateway.wan())
                        .put("stats", stats.json()).toString();
                }
                else if (action.equals("STATUS")) reply = s.equals("READY") ? (wakeFailed ? "START_FAILED" : "READY:" + epoch) : s;
                else if (!client.equals(instance)) reply = "IDENTITY";
                else if (action.equals("EVENTS")) reply = events(claim.matches("\\d{1,18}") ? Long.parseLong(claim) : 0);
                else if (action.equals("CLAIM") && s.equals("READY") && !ticket.isEmpty() && ticket.equals(claim) &&
                    SystemClock.elapsedRealtime() - issued < 30_000) {
                    reply = "CHECK:" + epoch; claimed = ticket; ticketRevived = pendingRevived; ticket = ""; wakeFailed = false;
                    stats.wakes++; if (ticketRevived) stats.revived++; stats.save();
                    note("CLAIM", "APK entered" + (ticketRevived ? " (was not running)" : ""));
                    handler.post(() -> describe(lastStatus));
                }
                // After a module wake the APK reports how its check ended ("<ticket>:<code>"), once per claimed ticket.
                else if (action.equals("RESULT") && !claimed.isEmpty() && claim.startsWith(claimed + ":")) {
                    String code = claim.substring(claimed.length() + 1);
                    claimed = "";
                    if (code.equals("SLOT_UPDATED") || code.equals("RECOVERED_VERIFIED")) {
                        stats.ipUpdates++; stats.save(); handler.post(() -> describe(lastStatus));
                    }
                    note("RESULT", code.matches("[A-Z0-9_]{1,64}") ? code : "?");
                    reply = "OK";
                }
                else reply = "REJECTED";
            }
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(reply); output.flush();
        } catch (Exception ignored) { android.util.Log.w("FuckPo0JiXianHelper", "IPC_" + ignored.getClass().getSimpleName()); }
    }
}
