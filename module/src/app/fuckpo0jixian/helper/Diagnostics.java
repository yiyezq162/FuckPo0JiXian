package app.fuckpo0jixian.helper;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** User-invoked root-only read-only snapshot. Never starts APK or a helper. */
public final class Diagnostics {
    public static void main(String[] args) {
        if (android.os.Process.myUid() != 0 || args.length != 0) return;
        File dir = new File("/data/adb/modules/fuckpo0jixian_helper");
        System.out.println("检查时间: " + System.currentTimeMillis());
        System.out.println("模块目录存在: " + dir.isDirectory());
        System.out.println("禁用标志: " + new File(dir, "disable").exists());
        System.out.println("待卸载标志: " + new File(dir, "remove").exists());
        System.out.println("标志仅表示配置意图，不证明监听或同步正在运行。");
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("fuckpo0jixian.runtime.v1", LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(2000);
            if (socket.getPeerCredentials().getUid() != 0) throw new SecurityException();
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeUTF("1"); out.writeUTF("DIAGNOSTICS"); out.writeUTF(""); out.writeUTF(""); out.flush();
            String report = new DataInputStream(socket.getInputStream()).readUTF();
            // Old helpers may reject this action; that is not a live health proof.
            new org.json.JSONObject(report).getString("status");
            System.out.println("本次 root 身份握手报告: " + report);
        } catch (Exception e) { System.out.println("本次未取得有效握手，运行状态未知: " + e.getClass().getSimpleName()); }
        try {
            File history = new File(dir, "last-report.json");
            if (history.length() > 2048) throw new IOException();
            System.out.println("历史生命周期报告（不能证明当前状态）: " +
                new String(Files.readAllBytes(history.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) { System.out.println("无可读历史生命周期报告。"); }
        System.out.println("APK 是否完成请求，请查看 APK 诊断；本检查没有 HTTP 或唤起动作。");
    }
}
