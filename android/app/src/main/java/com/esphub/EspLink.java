package com.esphub;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Satu koneksi WiFi ke satu ESP32 (hotspot). Semua HTTP lewat jaringan ini saja,
 * jadi data seluler HP tetap jalan dan tidak tercampur.
 */
final class EspLink implements AutoCloseable {

    static final String DEFAULT_HOST = "192.168.4.1";

    private final ConnectivityManager cm;
    private final ConnectivityManager.NetworkCallback callback;
    private final Network network;
    final String host;

    private EspLink(ConnectivityManager cm, ConnectivityManager.NetworkCallback cb, Network n, String host) {
        this.cm = cm;
        this.callback = cb;
        this.network = n;
        this.host = host;
    }

    /** Minta Android tersambung ke SSID ini. Memblok sampai tersambung, gagal, atau dibatalkan. */
    static EspLink open(Context ctx, String ssid, String pass, AtomicBoolean cancel) throws IOException {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (!pass.isEmpty()) spec.setWpa2Passphrase(pass);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec.build())
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        Network[] got = {null};
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { got[0] = n; latch.countDown(); }
            @Override public void onUnavailable() { latch.countDown(); }
        };
        // 60 detik: cukup untuk pengguna menekan "Hubungkan" di pop-up Android
        cm.requestNetwork(req, cb, 60_000);
        try {
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (cancel.get()) {
                    release(cm, cb);
                    throw new IOException("Dibatalkan");
                }
            }
        } catch (InterruptedException e) {
            release(cm, cb);
            throw new IOException("Dibatalkan");
        }
        if (got[0] == null) {
            release(cm, cb);
            throw new IOException("Tidak bisa masuk ke WiFi \"" + ssid + "\" (di luar jangkauan, password salah, atau ditolak)");
        }
        return new EspLink(cm, cb, got[0], findHost(cm, got[0]));
    }

    /**
     * Alamat ESP = gateway / DHCP server jaringan WiFi-nya (biasanya 192.168.4.1,
     * tapi bisa lain bila firmware memakai IP sendiri).
     */
    private static String findHost(ConnectivityManager cm, Network n) {
        for (int i = 0; i < 20; i++) {  // LinkProperties kadang belum lengkap tepat setelah tersambung
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Inet4Address dhcp = lp.getDhcpServerAddress();
                    if (dhcp != null && !dhcp.isAnyLocalAddress()) return dhcp.getHostAddress();
                }
                for (RouteInfo r : lp.getRoutes()) {
                    InetAddress gw = r.getGateway();
                    if (gw instanceof Inet4Address && !gw.isAnyLocalAddress()) return gw.getHostAddress();
                }
            }
            try { Thread.sleep(150); } catch (InterruptedException e) { break; }
        }
        return DEFAULT_HOST;
    }

    Network network() { return network; }

    HttpURLConnection http(String path) throws IOException {
        HttpURLConnection h = (HttpURLConnection) network.openConnection(new URL("http://" + host + path));
        h.setConnectTimeout(5000);
        h.setReadTimeout(15000);
        h.setUseCaches(false);
        return h;
    }

    /** GET teks, dicoba beberapa kali karena IP dari ESP kadang belum siap tepat setelah tersambung. */
    String getText(String path) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                HttpURLConnection h = http(path);
                try {
                    int code = h.getResponseCode();
                    if (code != 200) throw new HttpError(code);
                    return readAll(h.getInputStream());
                } finally {
                    h.disconnect();
                }
            } catch (HttpError e) {
                throw e;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
            }
        }
        throw new IOException("Sudah masuk WiFi, tapi ESP di " + host + " tidak menjawab. "
                + "Pastikan firmware EspHub sudah di-upload ke ESP ini."
                + (last != null ? "\n(" + last.getMessage() + ")" : ""));
    }

    /** POST form ke ESP. Mengembalikan isi jawaban, atau melempar error dengan pesan dari ESP. */
    String postForm(String path, String... kv) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection h = http(path);
            try {
                h.setRequestMethod("POST");
                h.setInstanceFollowRedirects(false);  // sebagian firmware menjawab 303 ke "/"
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                h.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = h.getOutputStream()) { out.write(data); }
                int code = h.getResponseCode();
                if (code >= 300 && code < 400) return "";  // tersimpan, ESP mengarahkan ke halaman utama
                if (code == 404) throw new HttpError(code);
                if (code != 200) {
                    InputStream es = h.getErrorStream();
                    throw new HttpError(code, es != null ? readAll(es) : "ESP menjawab " + code);
                }
                return readAll(h.getInputStream());
            } catch (HttpError e) {
                throw e;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            } finally {
                h.disconnect();
            }
        }
        throw new IOException("ESP di " + host + " tidak menjawab" + (last != null ? " (" + last.getMessage() + ")" : ""));
    }

    @Override public void close() {
        release(cm, callback);
    }

    private static void release(ConnectivityManager cm, ConnectivityManager.NetworkCallback cb) {
        try { cm.unregisterNetworkCallback(cb); } catch (RuntimeException ignored) { }
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int r; (r = s.read(buf)) > 0; ) bo.write(buf, 0, r);
            return bo.toString("UTF-8");
        }
    }

    /** ESP menjawab, tapi dengan kode error: tidak perlu dicoba ulang. */
    static final class HttpError extends IOException {
        final int code;
        HttpError(int code) {
            this(code, code == 404
                    ? "ESP tersambung, tapi firmware-nya bukan EspHub (alamat yang dicari tidak ada / 404)."
                    : "ESP menjawab " + code);
        }
        HttpError(int code, String msg) { super(msg); this.code = code; }
    }
}
