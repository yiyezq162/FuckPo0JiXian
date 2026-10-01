package app.fuckpo0jixian.helper;

import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * What the module has actually done, counted for good: wakes the APK confirmed, how many of those started an APK
 * that was not running, Po0 writes those wakes led to, and network / router changes noticed. Kept outside the module
 * folder so module updates keep it; uninstall.sh removes it. Counts only: no addresses, no event times.
 * The same numbers go into the root manager's module description, so its list shows whether the module earns its keep.
 */
final class Stats {
    static final File DIR = new File("/data/adb/fuckpo0jixian");
    private static final File FILE = new File(DIR, "stats.json");

    long wakes, revived, ipUpdates, networkChanges, gatewayChanges, since;

    static Stats load() {
        Stats s = new Stats();
        try {
            if (FILE.isFile() && FILE.length() < 4096) {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8));
                s.wakes = Math.max(0, o.optLong("wakes")); s.revived = Math.max(0, o.optLong("revived"));
                s.ipUpdates = Math.max(0, o.optLong("ipUpdates")); s.networkChanges = Math.max(0, o.optLong("networkChanges"));
                s.gatewayChanges = Math.max(0, o.optLong("gatewayChanges")); s.since = Math.max(0, o.optLong("since"));
            }
        } catch (Exception ignored) { }
        if (s.since == 0) s.since = System.currentTimeMillis();
        return s;
    }

    JSONObject json() throws Exception {
        return new JSONObject().put("wakes", wakes).put("revived", revived).put("ipUpdates", ipUpdates)
            .put("networkChanges", networkChanges).put("gatewayChanges", gatewayChanges).put("since", since);
    }

    synchronized void save() {
        android.util.AtomicFile file = new android.util.AtomicFile(FILE);
        FileOutputStream out = null;
        try {
            if (!DIR.isDirectory() && DIR.mkdirs()) { DIR.setReadable(false, false); DIR.setReadable(true, true); DIR.setExecutable(false, false); DIR.setExecutable(true, true); }
            out = file.startWrite();
            out.write(json().toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception e) { if (out != null) file.failWrite(out); }
    }

    /** One line for module.prop: the state first (a tick when it works), then what it has done. */
    String description(String status) {
        String head;
        switch (status) {
            case "READY": head = "✅ 模块已启用"; break;
            case "START_FAILED": head = "⚠️ 系统拒绝了唤起，正在重试"; break;
            case "STOPPED": head = "⏸ 待命中：应用处于标准模式、已暂停或被强行停止"; break;
            case "VERSION": head = "⚠️ 应用与模块版本不配套，请在应用中更新模块"; break;
            case "LOCKED": head = "🔒 等待手机首次解锁"; break;
            case "DISABLED": head = "⏹ 模块已停用"; break;
            default: head = "⚠️ 未找到配套的去他妈的鸡险应用";
        }
        List<String> parts = new ArrayList<>();
        parts.add("已更新 IP " + ipUpdates + " 次");
        parts.add("唤醒应用 " + wakes + " 次" + (revived > 0 ? "（其中拉起 " + revived + " 次）" : ""));
        parts.add("侦测网络变化 " + (networkChanges + gatewayChanges) + " 次");
        return head + " ｜ " + String.join(" · ", parts);
    }

    /**
     * Rewrites only the description line of module.prop (Magisk and KernelSU both list modules from it), atomically,
     * and only when the file says otherwise: a duplicate service.sh start (KernelSU also runs it at boot-completed)
     * writes "starting" over a running helper's line, and the next minute's lifecycle puts it back.
     * Called from the helper's single looper thread.
     */
    static void describe(File moduleDir, String text) {
        File prop = new File(moduleDir, "module.prop");
        try {
            List<String> lines = Files.readAllLines(prop.toPath(), StandardCharsets.UTF_8);
            if (lines.contains("description=" + text)) return;
            StringBuilder next = new StringBuilder();
            boolean found = false;
            for (String line : lines) {
                if (line.startsWith("description=")) { next.append("description=").append(text); found = true; }
                else next.append(line);
                next.append('\n');
            }
            if (!found) next.append("description=").append(text).append('\n');
            android.util.AtomicFile file = new android.util.AtomicFile(prop);
            FileOutputStream out = file.startWrite();
            try { out.write(next.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
            catch (Exception e) { file.failWrite(out); throw e; }
        } catch (Exception ignored) { }
    }
}
