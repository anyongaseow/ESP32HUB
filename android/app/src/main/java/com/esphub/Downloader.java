package com.esphub;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semua kerja jaringan berjalan berurutan di satu thread, karena HP hanya bisa
 * tersambung ke satu ESP pada satu waktu.
 *
 *  - open(key)         : sambung ke ESP, ambil daftar .csv, lalu tetap tersambung
 *  - downloadFile(...) : unduh satu file (sambung ulang otomatis bila perlu)
 *  - fullDownload(key) : sambung, unduh semua file, lalu putus (untuk "Unduh semua")
 *
 * File disimpan ke Download/ESP32Hub/&lt;ID ESP&gt;/.
 */
final class Downloader {

    static final int IDLE = 0, QUEUED = 1, CONNECTING = 2, LISTING = 3, READY = 4, DOWNLOADING = 5,
            DONE = 6, ERROR = 7, CANCELLED = 8;
    static final int F_WAIT = 0, F_QUEUED = 1, F_RUN = 2, F_OK = 3, F_ERR = 4;

    static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/ESP32Hub/";
    private static final long IDLE_DISCONNECT_MS = 5 * 60_000;

    static final class FileState {
        final String path;
        final String url;  // alamat unduh di ESP, mis. "/file?n=..." atau "/download?file=..."
        volatile long size, done;
        volatile int st = F_WAIT;
        volatile Uri saved;        // lokasi file di HP setelah berhasil diunduh
        volatile String savedName; // nama file di HP (dengan tanggal)
        FileState(String path, String url, long size) { this.path = path; this.url = url; this.size = size; }
    }

    static final class Job {
        volatile int st = IDLE;
        volatile String msg = "";
        volatile List<FileState> files = Collections.emptyList();
        volatile String pageDate;  // tanggal di halaman web ESP, cadangan untuk nama file

        boolean active() {
            return st == QUEUED || st == CONNECTING || st == LISTING || st == DOWNLOADING;
        }
    }

    interface Listener {
        void onJob(long key);
        void onBusy(boolean busy);
    }

    private static final int T_OPEN = 0, T_FILE = 1, T_FULL = 2, T_CLOSE = 3;

    private static final class Task {
        final int type;
        final long key;
        final FileState file;
        Task(int type, long key, FileState file) { this.type = type; this.key = key; this.file = file; }
    }

    private static Downloader instance;

    static synchronized Downloader get(Context c) {
        if (instance == null) instance = new Downloader(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Long, Job> jobs = new HashMap<>();
    private final LinkedBlockingDeque<Task> queue = new LinkedBlockingDeque<>();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile boolean working;
    private volatile boolean hold;          // true selama browser ESP dibuka
    private volatile EspLink current;       // salinan "link" untuk dibaca thread UI
    private volatile long connectedKey;
    private Listener listener;

    // hanya disentuh oleh thread worker
    private EspLink link;
    private long linkKey;

    private Downloader(Context c) {
        ctx = c;
        Thread t = new Thread(this::loop, "downloader");
        t.setDaemon(true);
        t.start();
    }

    void setListener(Listener l) { listener = l; }

    void clearListener(Listener l) { if (listener == l) listener = null; }

    /** Koneksi ESP yang sedang aktif, atau null. */
    EspLink currentLink() { return current; }

    /** Tahan koneksi (tidak diputus otomatis karena tidak dipakai). */
    void setHold(boolean h) { hold = h; }

    synchronized Job job(long key) {
        Job j = jobs.get(key);
        if (j == null) { j = new Job(); jobs.put(key, j); }
        return j;
    }

    /** Sedang bekerja atau masih ada antrian. */
    boolean isBusy() { return working || !queue.isEmpty(); }

    /** Kunci ESP yang sedang tersambung, 0 bila tidak ada. */
    long connectedKey() { return connectedKey; }

    /** Jumlah ESP yang masih antri untuk "Unduh semua". */
    int pendingFull() {
        int n = 0;
        for (Task t : queue) if (t.type == T_FULL) n++;
        return n;
    }

    // ---------------------------------------------------------------- perintah dari UI

    synchronized void open(long key) {
        Job j = job(key);
        if (j.active()) return;
        j.st = QUEUED;
        j.msg = "Menunggu giliran";
        queue.add(new Task(T_OPEN, key, null));
        changed(key);
    }

    synchronized void downloadFile(long key, FileState f) {
        if (f.st == F_QUEUED || f.st == F_RUN) return;
        f.st = F_QUEUED;
        f.done = 0;
        queue.add(new Task(T_FILE, key, f));
        changed(key);
    }

    synchronized void downloadAllFiles(long key) {
        for (FileState f : job(key).files) if (f.st != F_OK) downloadFile(key, f);
    }

    synchronized void fullDownload(long key) {
        Job j = job(key);
        if (j.active()) return;
        j.st = QUEUED;
        j.msg = "Menunggu giliran";
        j.files = Collections.emptyList();
        queue.add(new Task(T_FULL, key, null));
        changed(key);
    }

    /** Batalkan semua antrian dan pekerjaan yang sedang jalan, lalu putuskan WiFi ESP. */
    synchronized void stopAll() {
        for (Task t : queue) {
            Job j = job(t.key);
            if (t.file != null) t.file.st = F_WAIT;
            else { j.st = CANCELLED; j.msg = "Dibatalkan"; }
            changed(t.key);
        }
        queue.clear();
        cancel.set(true);
        queue.add(new Task(T_CLOSE, 0, null));
    }

    // ---------------------------------------------------------------- thread worker

    private void loop() {
        while (true) {
            Task t;
            try {
                t = link != null ? queue.poll(IDLE_DISCONNECT_MS, TimeUnit.MILLISECONDS) : queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (t == null) {  // lama tidak dipakai: lepas WiFi ESP
                if (!hold) closeLink("Terputus (tidak dipakai)");
                continue;
            }
            if (t.type == T_CLOSE) {
                closeLink("Terputus");
                cancel.set(false);
                postBusy();
                continue;
            }
            working = true;
            postBusy();
            run(t);
            working = false;
            postBusy();
        }
    }

    private void run(Task t) {
        Store.Esp esp = Store.get(ctx).find(t.key);
        Job j = job(t.key);
        if (esp == null) return;
        try {
            switch (t.type) {
                case T_OPEN:
                    ensureLink(esp, j);
                    list(j, esp.key);
                    j.st = READY;
                    j.msg = j.files.isEmpty() ? "Tersambung, tetapi tidak ada file .csv" : readyMsg(j);
                    break;
                case T_FILE:
                    ensureLink(esp, j);
                    j.st = DOWNLOADING;
                    j.msg = "Mengunduh " + shortName(t.file.path) + "…";
                    t.file.st = F_RUN;
                    changed(esp.key);
                    downloadWithRetry(esp, t.file, j);
                    j.st = READY;
                    j.msg = readyMsg(j);
                    break;
                case T_FULL:
                    ensureLink(esp, j);
                    list(j, esp.key);
                    if (j.files.isEmpty()) {
                        j.st = DONE;
                        j.msg = "Tidak ada file .csv";
                    } else {
                        j.st = DOWNLOADING;
                        int ok = 0, i = 0;
                        for (FileState f : j.files) {
                            if (cancel.get()) throw new IOException("Dibatalkan");
                            j.msg = "Mengunduh " + (++i) + " dari " + j.files.size();
                            f.st = F_RUN;
                            changed(esp.key);
                            if (downloadWithRetry(esp, f, j)) ok++;
                        }
                        j.st = ok == j.files.size() ? DONE : ERROR;
                        j.msg = ok == j.files.size() ? "Selesai, " + ok + " file" : ok + " dari " + j.files.size() + " file berhasil";
                    }
                    changed(esp.key);
                    closeLink(null);
                    return;
            }
        } catch (IOException e) {
            boolean cancelled = cancel.get();
            j.st = cancelled ? CANCELLED : ERROR;
            j.msg = cancelled ? "Dibatalkan" : String.valueOf(e.getMessage());
            for (FileState f : j.files) {
                if (f.st == F_RUN || f.st == F_QUEUED) f.st = cancelled ? F_WAIT : F_ERR;
            }
            changed(esp.key);
            closeLink(null);
            return;
        }
        changed(esp.key);
    }

    private void ensureLink(Store.Esp esp, Job j) throws IOException {
        if (link != null && linkKey == esp.key) return;
        closeLink("Terputus");
        j.st = CONNECTING;
        j.msg = "Menyambung ke " + esp.ssid + "…";
        changed(esp.key);
        link = EspLink.open(ctx, esp.ssid, esp.pass, cancel);
        current = link;
        linkKey = esp.key;
        connectedKey = esp.key;
        postBusy();
    }

    /** Lepas WiFi ESP. msg != null: tulis pesan itu di kartunya bila sedang siap. */
    private void closeLink(String msg) {
        if (link == null) return;
        long key = linkKey;
        link.close();
        link = null;
        current = null;
        linkKey = 0;
        connectedKey = 0;
        Job j = job(key);
        if (msg != null && j.st == READY) {
            j.st = IDLE;
            j.msg = msg;
        }
        changed(key);
        postBusy();
    }

    private void list(Job j, long key) throws IOException {
        j.st = LISTING;
        j.msg = "Membaca daftar file…";
        j.pageDate = null;
        changed(key);
        try {
            j.files = parseList(link.getText("/list"));  // firmware EspHub
        } catch (EspLink.HttpError e) {
            if (e.code != 404 && e.code != 405) throw e;
            // Firmware lain: cari link unduhan dari halaman utama
            j.files = listFromPage(j);
        }
    }

    /** Alamat yang dicoba bila halaman tidak menyebut link unduhan sama sekali. */
    private static final String[] COMMON_URLS = {"/api/data/download", "/download", "/getData", "/data.csv", "/log.csv"};

    private List<FileState> listFromPage(Job j) throws IOException {
        String html = link.getText("/");
        j.pageDate = CsvSaver.firstDate(html.replaceAll("<[^>]+>", " "));
        List<FileState> files = parseHtml(html);  // gaya "download?file=nama.csv"
        if (!files.isEmpty()) return files;

        // Gaya "/api/data/download": alamat tanpa nama file, bisa di HTML atau di file .js
        Set<String> urls = new LinkedHashSet<>(findDownloadUrls(html));
        Matcher sm = SCRIPT_SRC.matcher(html);
        for (int n = 0; sm.find() && n < 3; n++) {
            try {
                urls.addAll(findDownloadUrls(link.getText(toPath(sm.group(1)))));
            } catch (IOException ignored) {
            }
        }
        if (urls.isEmpty()) urls.addAll(Arrays.asList(COMMON_URLS));

        List<FileState> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String u : urls) {
            FileState f = probe(u);
            if (f == null) continue;
            String name = f.path;
            for (int i = 2; !names.add(name); i++) name = f.path.replace(".csv", "_" + i + ".csv");
            out.add(name.equals(f.path) ? f : new FileState(name, f.url, f.size));
        }
        return Collections.unmodifiableList(out);
    }

    /** Cek satu alamat: bila jawabannya file CSV, ambil nama (Content-Disposition) dan ukurannya. */
    private FileState probe(String url) {
        HttpURLConnection h = null;
        try {
            h = link.http(url);
            if (h.getResponseCode() != 200) return null;
            String type = String.valueOf(h.getContentType()).toLowerCase(Locale.ROOT);
            String name = dispositionName(h.getHeaderField("Content-Disposition"));
            boolean csv = type.contains("csv") || url.toLowerCase(Locale.ROOT).endsWith(".csv")
                    || (name != null && name.toLowerCase(Locale.ROOT).endsWith(".csv"))
                    || (url.toLowerCase(Locale.ROOT).contains("download") && !type.contains("html") && !type.contains("json"));
            if (!csv) return null;
            if (name == null) name = guessName(url);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) name += ".csv";
            return new FileState("/" + name, url, h.getContentLengthLong());
        } catch (IOException e) {
            return null;
        } finally {
            if (h != null) h.disconnect();  // cukup header-nya; isi file diunduh nanti
        }
    }

    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script[^>]+src\\s*=\\s*['\"]([^'\"]+\\.js)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_URL = Pattern.compile(
            "['\"`](?:https?://[^/'\"`\\s]+)?(/[A-Za-z0-9_\\-./%]*)['\"`]");
    private static final Pattern DISPOSITION = Pattern.compile(
            "filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    /** Semua alamat dalam tanda kutip yang mengandung "download" atau berakhiran ".csv". */
    /** Alamat yang tidak boleh dipanggil sama sekali (bisa menghapus data / mengubah alat). */
    private static final Pattern UNSAFE = Pattern.compile(
            "delete|remove|clear|erase|format|restart|reboot|reset|set|save|update|upload|config|calib|wifi|ota|time",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_WORD = Pattern.compile(
            "download|data|log|csv|export|record|history|file", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATIC_FILE = Pattern.compile(
            "\\.(js|css|png|jpe?g|gif|svg|ico|html?|json|txt|woff2?)$", Pattern.CASE_INSENSITIVE);

    /**
     * Calon alamat file CSV di halaman/JavaScript: yang mengandung "download", berakhiran ".csv",
     * atau memuat kata seperti data/log/export (mis. "/api/data/download", "/getData").
     * Hanya dibaca dengan GET, dan hanya dianggap file bila ESP menjawab dengan CSV (lihat probe).
     */
    static List<String> findDownloadUrls(String text) {
        List<String> first = new ArrayList<>(), rest = new ArrayList<>();
        Matcher m = QUOTED_URL.matcher(text);
        while (m.find()) {
            String p = m.group(1), low = p.toLowerCase(Locale.ROOT);
            if (p.length() < 2 || UNSAFE.matcher(low).find() || STATIC_FILE.matcher(low).find()) continue;
            if (first.contains(p) || rest.contains(p)) continue;
            if (low.contains("download") || low.endsWith(".csv")) first.add(p);
            else if (DATA_WORD.matcher(low).find()) rest.add(p);
        }
        first.addAll(rest);
        return first.size() > 8 ? first.subList(0, 8) : first;
    }

    static String dispositionName(String header) {
        if (header == null) return null;
        Matcher m = DISPOSITION.matcher(header);
        if (!m.find()) return null;
        String n = Uri.decode(m.group(1).trim());
        n = n.substring(n.lastIndexOf('/') + 1);
        return n.isEmpty() ? null : n;
    }

    /** "/api/data/download" -> "data", "/getData" -> "data", "/log.csv" -> "log.csv". */
    static String guessName(String url) {
        String[] parts = url.split("[/?]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i], s = p.toLowerCase(Locale.ROOT);
            if (s.isEmpty() || s.equals("download") || s.equals("api") || s.equals("get") || s.equals("file")) continue;
            for (String prefix : new String[]{"download", "get"}) {
                if (s.startsWith(prefix) && s.length() > prefix.length()) {
                    p = p.substring(prefix.length());
                    p = Character.toLowerCase(p.charAt(0)) + p.substring(1);
                    break;
                }
            }
            return p.replaceAll("^[_-]+", "");
        }
        return "data";
    }

    private static String toPath(String src) {
        if (src.matches("(?i)https?://.*")) {
            int slash = src.indexOf('/', src.indexOf("//") + 2);
            return slash >= 0 ? src.substring(slash) : "/";
        }
        return src.startsWith("/") ? src : "/" + src;
    }

    private static String readyMsg(Job j) {
        int ok = 0, waiting = 0;
        for (FileState f : j.files) {
            if (f.st == F_OK) ok++;
            if (f.st == F_QUEUED) waiting++;
        }
        if (waiting > 0) return "Tersambung · " + waiting + " file antri";
        return "Tersambung · ketuk file untuk mengunduh"
                + (ok > 0 ? " (" + ok + " sudah diunduh · ketuk untuk membuka, tekan lama untuk unduh ulang)" : "");
    }

    private boolean downloadWithRetry(Store.Esp esp, FileState f, Job j) throws IOException {
        // tanggal cadangan bila di dalam file tidak ada tanggal: dari web ESP, lalu hari ini
        String fallback = j.pageDate != null ? j.pageDate : CsvSaver.today();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                downloadFile(esp, f, fallback);
                f.st = F_OK;
                return true;
            } catch (IOException e) {
                if (cancel.get()) throw e;
            }
        }
        f.st = F_ERR;
        return false;
    }

    private void downloadFile(Store.Esp esp, FileState f, String fallbackDate) throws IOException {
        f.done = 0;
        HttpURLConnection h = link.http(f.url);
        try {
            if (h.getResponseCode() != 200) throw new IOException("ESP menjawab " + h.getResponseCode());
            long len = h.getContentLengthLong();
            if (len >= 0) f.size = len;
            long[] lastPost = {0};
            try (InputStream in = h.getInputStream()) {
                CsvSaver.Result r = CsvSaver.save(ctx, esp.id, fileName(f.path), "text/csv", in, len, fallbackDate,
                        cancel, done -> {
                            f.done = done;
                            long now = SystemClock.uptimeMillis();
                            if (now - lastPost[0] > 200) { lastPost[0] = now; changed(esp.key); }
                        });
                f.saved = r.uri;
                f.savedName = r.name;
            }
        } finally {
            h.disconnect();
        }
    }

    private static List<FileState> parseList(String json) throws IOException {
        try {
            JSONArray a = new JSONArray(json);
            List<FileState> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String n = o.getString("n");
                out.add(new FileState(n, "/file?n=" + URLEncoder.encode(n, "UTF-8"), o.optLong("s", -1)));
            }
            Collections.sort(out, (x, y) -> Store.naturalCompare(x.path, y.path));
            return Collections.unmodifiableList(out);
        } catch (JSONException e) {
            throw new IOException("Daftar file dari ESP tidak valid (firmware bukan EspHub?)");
        }
    }

    private static final Pattern HREF = Pattern.compile(
            "href\\s*=\\s*['\"]([^'\"]*[?&](?:file|n|name|path|f)=([^'\"&]+)[^'\"]*)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(bytes?|B|KB|MB)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPTY_CELL = Pattern.compile("<td[^>]*>\\s*-\\s*</td>", Pattern.CASE_INSENSITIVE);

    /**
     * Untuk firmware yang tidak punya /list: cari link unduhan .csv di halaman HTML,
     * mis. &lt;a href='/download?file=/flow_log_1.csv'&gt;, beserta ukurannya di baris tabel yang sama.
     * Baris yang ukurannya "-" (file belum ada) dilewati.
     */
    static List<FileState> parseHtml(String html) {
        Map<String, FileState> found = new LinkedHashMap<>();
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String href = m.group(1).replace("&amp;", "&");
            String route = href.contains("?") ? href.substring(0, href.indexOf('?')).toLowerCase(Locale.ROOT) : "";
            if (route.contains("delete") || route.contains("remove")) continue;
            if (!route.contains("download") && !route.contains("file") && !route.contains("get")) continue;
            String name = Uri.decode(m.group(2).replace("&amp;", "&")).trim();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".csv") || found.containsKey(name)) continue;

            int rs = html.lastIndexOf("<tr", m.start()), re = html.indexOf("</tr>", m.end());
            String row = rs >= 0 && re > rs ? html.substring(rs, re) : "";
            long size = -1;
            Matcher sm = SIZE.matcher(row.replaceAll("<[^>]+>", " "));
            if (sm.find()) {
                double v = Double.parseDouble(sm.group(1).replace(',', '.'));
                String unit = sm.group(2).toUpperCase(Locale.ROOT);
                size = Math.round(unit.equals("KB") ? v * 1024 : unit.equals("MB") ? v * 1048576 : v);
            } else if (EMPTY_CELL.matcher(row).find()) {
                continue;  // file belum ada di ESP
            }

            if (href.matches("(?i)https?://.*")) {
                int slash = href.indexOf('/', href.indexOf("//") + 2);
                href = slash >= 0 ? href.substring(slash) : "/";
            }
            if (!href.startsWith("/")) href = "/" + href;
            found.put(name, new FileState(name, href.replace(" ", "%20"), size));
        }
        List<FileState> out = new ArrayList<>(found.values());
        Collections.sort(out, (x, y) -> Store.naturalCompare(x.path, y.path));
        return Collections.unmodifiableList(out);
    }

    static String shortName(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /** "/data/log 1.csv" -> "data_log 1.csv" (subfolder di ESP jadi awalan nama). */
    static String fileName(String path) {
        return safeName(shortName(path).replace('/', '_'));
    }

    static String safeName(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return r.isEmpty() ? "ESP" : r;
    }

    private void changed(long key) {
        main.post(() -> { if (listener != null) listener.onJob(key); });
    }

    private void postBusy() {
        main.post(() -> { if (listener != null) listener.onBusy(isBusy()); });
    }
}
