package app.allowmate.helper;

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

/** Root-only event helper. No HTTP, credential read, shell interpolation or business state. */
public final class Main {
    private static final String PKG = "app.allowmate";
    private static final File DIR = new File("/data/adb/modules/allowmate_helper");
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
    private static final Runnable wake = Main::trigger;
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0 || args.length != 0) return;
        // Kernel releases this lock on exit/crash; no stale PID killing or PID reuse hazard.
        RandomAccessFile lockFile = new RandomAccessFile(new File(DIR, "helper.lock"), "rw");
        FileLock lock = lockFile.getChannel().tryLock();
        if (lock == null) return;
        certificate = new String(Files.readAllBytes(new File(DIR, "certificate.sha256").toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        context = (Context) at.getMethod("getSystemContext").invoke(thread);
        handler = new Handler(Looper.getMainLooper());
        LocalServerSocket server = new LocalServerSocket("allowmate.runtime.v1");
        Thread ipc = new Thread(() -> serve(server), "allowmate-ipc");
        ipc.setDaemon(true); ipc.start();
        moduleObserver = new FileObserver(DIR.getAbsolutePath(), FileObserver.CREATE | FileObserver.DELETE | FileObserver.DELETE_SELF | FileObserver.MOVED_TO) {
            public void onEvent(int event, String path) { handler.post(Main::lifecycle); }
        };
        moduleObserver.startWatching();
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                private String observed = "";
                public void onAvailable(Network n) { event(); }
                public void onLost(Network n) { event(); }
                public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) { event(); }
                private void event() { handler.post(() -> {
                    Network n = cm.getActiveNetwork();
                    NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
                    String key = n + ":" + (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                    if (key.equals(observed)) return;
                    observed = key; handler.removeCallbacks(wake); handler.postDelayed(wake, 500);
                }); }
            });
        } catch (Exception denied) {
            // late_start can precede publication of ConnectivityService. A live but
            // permanently unregistered helper would also block the boot-completed hook.
            // Exit so the module supervisor's bounded backoff can create a fresh context.
            android.util.Log.w("AllowMateHelper", "CALLBACK_START_FAILED_" + denied.getClass().getSimpleName());
            throw new IllegalStateException("CALLBACK_START_FAILED", denied);
        }
        // Lifecycle watchdog only: no HTTP, no network probing, no wake lock, no periodic APK wake.
        handler.post(new Runnable() { public void run() { lifecycle(); handler.postDelayed(this, 60_000); } });
        Looper.loop();
        lock.release(); lockFile.close();
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
            if (p.getLongVersionCode() != 4) return "VERSION";
            if (!context.getSystemService(UserManager.class).isUserUnlocked()) return "LOCKED";
            File config = new File(p.applicationInfo.dataDir, "no_backup/runtime-v1.json");
            if (!config.isFile() || config.length() > 1024) return "STOPPED";
            JSONObject data = new JSONObject(new String(Files.readAllBytes(config.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            if (data.getInt("protocol") != 1) return "VERSION";
            String next = data.getString("instance");
            if (!next.matches("[a-f0-9-]{36}")) return "IDENTITY";
            if (!instance.equals(next)) { instance = next; ticket = ""; }
            String nextGeneration = data.getString("generation");
            if (!generation.equals(nextGeneration)) { generation = nextGeneration; epoch = UUID.randomUUID().toString(); ticket = ""; wakeFailed = false; }
            if (!data.getBoolean("enabled")) { ticket = ""; return "STOPPED"; }
            if (lastStatus.equals("CALLBACK_FAILED")) return lastStatus;
            return "READY";
        } catch (Exception e) { return "UNAVAILABLE"; }
    }
    private static synchronized void lifecycle() {
        String s = state();
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
    private static synchronized void trigger() {
        if (!state().equals("READY")) return;
        long now = SystemClock.elapsedRealtime();
        // Event storm bound only; APK owns business debounce/rate/backoff.
        if (lastWake != 0 && now - lastWake < 15_000) {
            handler.removeCallbacks(wake); handler.postDelayed(wake, 15_000 - (now - lastWake)); return;
        }
        lastWake = now; issued = now; ticket = UUID.randomUUID().toString();
        final String pending = ticket;
        // Some OEMs reject an otherwise valid start while am still exits normally.
        // Only the APK claiming this one-use ticket confirms entry. This one-shot
        // timer is not a poll, wake lock, or proof of an HTTP request completing.
        handler.postDelayed(() -> {
            synchronized (Main.class) {
                if (state().equals("READY") && pending.equals(ticket)) {
                    wakeFailed = true; ticket = "";
                    android.util.Log.w("AllowMateHelper", "WAKE_UNCONFIRMED");
                }
            }
        }, 30_000);
        try {
            java.lang.Process process = new ProcessBuilder("/system/bin/am", "start-foreground-service", "--user", "0",
                "-n", "app.allowmate/.RuntimeSyncService", "-a", "app.allowmate.RUNTIME_CHECK", "-f", "0x10", "--es", "ticket", ticket)
                .redirectOutput(new File("/dev/null")).redirectError(new File("/dev/null")).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); wakeFailed = true; }
            else if (process.exitValue() != 0) wakeFailed = true;
        } catch (Exception e) { wakeFailed = true; }
    }
    private static void serve(LocalServerSocket server) {
        while (true) try (LocalSocket socket = server.accept()) {
            socket.setSoTimeout(1500);
            int caller = socket.getPeerCredentials().getUid();
            if (caller != 0 && caller != identity().applicationInfo.uid) {
                android.util.Log.w("AllowMateHelper", "IPC_UID_REJECTED"); continue;
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
                        .put("status", s.equals("READY") ? (wakeFailed ? "START_FAILED" : "READY:" + epoch) : s).toString();
                }
                else if (action.equals("STATUS")) reply = s.equals("READY") ? (wakeFailed ? "START_FAILED" : "READY:" + epoch) : s;
                else if (!client.equals(instance)) reply = "IDENTITY";
                else if (action.equals("CLAIM") && s.equals("READY") && !ticket.isEmpty() && ticket.equals(claim) &&
                    SystemClock.elapsedRealtime() - issued < 30_000) { reply = "CHECK:" + epoch; ticket = ""; wakeFailed = false; }
                else reply = "REJECTED";
            }
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(reply); output.flush();
        } catch (Exception ignored) { android.util.Log.w("AllowMateHelper", "IPC_" + ignored.getClass().getSimpleName()); }
    }
}
