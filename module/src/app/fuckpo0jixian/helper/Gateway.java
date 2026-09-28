package app.fuckpo0jixian.helper;

import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches the home router's WAN address on the current Wi-Fi, so a PPPoE redial (same Wi-Fi, same LAN address, new
 * public IP) is noticed within about half a minute instead of at the next fallback tick. LAN only: NAT-PMP to the
 * default gateway, else UPnP IGD found by SSDP on the LAN, and every socket is bound to the Wi-Fi network so a VPN
 * never carries it. Nothing is sent to the internet. Runs on its own thread; the Handler clock pauses in deep sleep,
 * so it never wakes the device.
 */
final class Gateway {
    interface Listener {
        void changed(String wan, String previous, String method);
        void note(String kind, String detail);
    }

    private static final long POLL_MS = 30_000, PRIVATE_POLL_MS = 5 * 60_000, REDISCOVER_MS = 30 * 60_000;
    private static final Pattern SERVICE = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE = Pattern.compile("<serviceType>\\s*(urn:schemas-upnp-org:service:WAN(?:IP|PPP)Connection:\\d)\\s*</serviceType>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL = Pattern.compile("<controlURL>\\s*([^<\\s]+)\\s*</controlURL>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE = Pattern.compile("<URLBase>\\s*([^<\\s]+)\\s*</URLBase>", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL = Pattern.compile("<NewExternalIPAddress>\\s*([0-9.]{7,15})\\s*</NewExternalIPAddress>", Pattern.CASE_INSENSITIVE);

    private final Listener listener;
    private final Handler handler;
    private int generation;
    private Network network;
    private InetAddress gateway;
    private String method;          // null until discovered: NATPMP or UPNP
    private URL control;            // UPnP control URL
    private String service;         // UPnP service type
    private int failures;
    private volatile String wan, status = "OFF";

    Gateway(Listener listener) {
        this.listener = listener;
        HandlerThread thread = new HandlerThread("fuckpo0jixian-gateway");
        thread.setDaemon(true);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    String wan() { return wan; }
    String status() { return status; }
    Handler handler() { return handler; }

    /** Wi-Fi [network] with its IPv4 default gateway, or null for none; unchanged arguments are a no-op. */
    void watch(Network next, InetAddress nextGateway) {
        handler.post(() -> {
            if (java.util.Objects.equals(next, network) && java.util.Objects.equals(nextGateway, gateway)) return;
            generation++;
            handler.removeCallbacksAndMessages(null);
            network = next; gateway = nextGateway; method = null; control = null; service = null; failures = 0; wan = null;
            status = next == null || nextGateway == null ? "OFF" : "DISCOVERING";
            if (next != null && nextGateway != null) schedule(3_000); // let DHCP and the router settle
        });
    }

    private void schedule(long delay) {
        final int expected = generation;
        handler.postDelayed(() -> { if (expected == generation) poll(); }, delay);
    }

    private void poll() {
        String observed = null;
        try {
            if (method == null) observed = discover();
            else observed = "NATPMP".equals(method) ? natPmp() : upnpQuery();
        } catch (Exception e) { observed = null; }
        if (observed == null) {
            if (method == null) {
                status = "UNSUPPORTED";
                listener.note("GATEWAY", "no NAT-PMP or UPnP answer; retry in 30min");
                schedule(REDISCOVER_MS);
            } else if (++failures >= 3) {
                listener.note("GATEWAY", method + " stopped answering; rediscovering");
                method = null; control = null; service = null; failures = 0; status = "DISCOVERING";
                schedule(PRIVATE_POLL_MS);
            } else schedule(POLL_MS);
            return;
        }
        failures = 0;
        boolean inner = internal(observed);
        status = method + (inner ? "_PRIVATE" : "");
        String previous = wan;
        wan = observed;
        if (previous == null) listener.note("GATEWAY", method + " wan=" + observed + (inner ? " (router behind another NAT)" : ""));
        else if (!previous.equals(observed)) listener.changed(observed, previous, method);
        // A router behind another NAT (e.g. the ISP modem in router mode) rarely moves; ask less often.
        schedule(inner ? PRIVATE_POLL_MS : POLL_MS);
    }

    private String discover() throws IOException {
        String found = natPmp();
        if (found != null) { method = "NATPMP"; return found; }
        if (upnpDiscover()) {
            found = upnpQuery();
            if (found != null) { method = "UPNP"; return found; }
        }
        return null;
    }

    /** RFC 6886 external address request to the default gateway. */
    private String natPmp() throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            network.bindSocket(socket);
            socket.setSoTimeout(700);
            byte[] request = {0, 0};
            for (int attempt = 0; attempt < 2; attempt++) {
                socket.send(new DatagramPacket(request, request.length, gateway, 5351));
                byte[] buffer = new byte[16];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(reply); } catch (SocketTimeoutException timeout) { continue; }
                if (!gateway.equals(reply.getAddress()) || reply.getLength() < 12 || buffer[0] != 0 || (buffer[1] & 0xff) != 128) continue;
                if (buffer[2] != 0 || buffer[3] != 0) return null; // result code
                return (buffer[8] & 0xff) + "." + (buffer[9] & 0xff) + "." + (buffer[10] & 0xff) + "." + (buffer[11] & 0xff);
            }
        } catch (PortUnreachableException closed) { return null; }
        return null;
    }

    /** SSDP search on the LAN; accepts only a description served by the device that answered, on a private address. */
    private boolean upnpDiscover() throws IOException {
        long deadline = SystemClock.elapsedRealtime() + 2_500;
        try (DatagramSocket socket = new DatagramSocket()) {
            network.bindSocket(socket);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (String target : new String[] {"urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:device:InternetGatewayDevice:2", "urn:schemas-upnp-org:service:WANIPConnection:1"}) {
                byte[] search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + target + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(search, search.length, group, 1900));
            }
            while (true) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return false;
                socket.setSoTimeout((int) left);
                byte[] buffer = new byte[2048];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(reply); } catch (SocketTimeoutException timeout) { return false; }
                if (!(reply.getAddress() instanceof Inet4Address) || !reply.getAddress().isSiteLocalAddress()) continue;
                String location = header(new String(buffer, 0, reply.getLength(), StandardCharsets.US_ASCII), "location");
                if (location == null) continue;
                URL url;
                try { url = new URL(location); } catch (MalformedURLException bad) { continue; }
                if (!"http".equals(url.getProtocol()) || !reply.getAddress().getHostAddress().equals(url.getHost())) continue;
                String description = fetch(url, null, null);
                if (description == null) continue;
                Matcher base = BASE.matcher(description);
                URL root = url;
                if (base.find()) try { root = new URL(base.group(1)); } catch (MalformedURLException ignored) { }
                Matcher services = SERVICE.matcher(description);
                while (services.find()) {
                    Matcher type = TYPE.matcher(services.group(1)), path = CONTROL.matcher(services.group(1));
                    if (!type.find() || !path.find()) continue;
                    URL candidate = new URL(root, path.group(1));
                    if (!"http".equals(candidate.getProtocol()) || !url.getHost().equals(candidate.getHost())) continue;
                    control = candidate; service = type.group(1);
                    return true;
                }
            }
        }
    }

    private String upnpQuery() throws IOException {
        if (control == null) return null;
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:GetExternalIPAddress xmlns:u=\"" + service +
            "\"/></s:Body></s:Envelope>";
        String reply = fetch(control, body, "\"" + service + "#GetExternalIPAddress\"");
        if (reply == null) return null;
        Matcher m = EXTERNAL.matcher(reply);
        return m.find() && valid(m.group(1)) ? m.group(1) : null;
    }

    /** GET, or a SOAP POST when [body] is set; LAN only, small and quick. */
    private String fetch(URL url, String body, String action) throws IOException {
        HttpURLConnection c = (HttpURLConnection) network.openConnection(url, Proxy.NO_PROXY);
        try {
            c.setConnectTimeout(2_000); c.setReadTimeout(2_000); c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                c.setRequestProperty("SOAPAction", action);
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) { out.write(buffer, 0, n); if (out.size() > 65_536) return null; }
                return out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    private static String header(String response, String name) {
        for (String line : response.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
        }
        return null;
    }

    static boolean valid(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            try { int v = Integer.parseInt(p); if (v < 0 || v > 255 || !p.equals(String.valueOf(v))) return false; }
            catch (NumberFormatException e) { return false; }
        }
        return !ip.equals("0.0.0.0");
    }

    /** Private, carrier-grade NAT, loopback or link-local: the router itself sits behind another NAT. */
    static boolean internal(String ip) {
        String[] p = ip.split("\\.");
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
        return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) ||
            (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254);
    }
}
