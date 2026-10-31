package com.esphub;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Menyimpan unduhan ke Download/ESP32Hub/&lt;ID ESP&gt;/ dengan tanggal di nama file.
 * Tanggal diambil dari isi file (tanggal pertama dan terakhir), bila tidak ada dari
 * tanggal cadangan (halaman web ESP atau hari ini).
 * Contoh: data.csv -> data_2026-10-01_2026-10-31.csv, atau data_2026-10-31.csv bila satu hari.
 */
final class CsvSaver {

    interface Progress {
        void on(long done);
    }

    static final class Result {
        final Uri uri;
        final String name;
        Result(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    private static final int PEEK = 8192;  // cukup beberapa baris awal & akhir untuk mencari tanggal

    // 2026-10-31, 2026/10/31, 2026.10.31  |  31-10-2026, 31/10/2026, 31.10.2026
    private static final Pattern YMD = Pattern.compile("(?<!\\d)(20\\d\\d)[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)");
    private static final Pattern DMY = Pattern.compile("(?<!\\d)(\\d{1,2})[-/.](\\d{1,2})[-/.](20\\d\\d)(?!\\d)");

    private CsvSaver() { }

    static Result save(Context ctx, String espId, String baseName, String mime, InputStream in, long len,
                       String fallbackDate, AtomicBoolean cancel, Progress progress) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String dir = Downloader.FOLDER + Downloader.safeName(espId) + "/";
        ContentValues v = new ContentValues();
        // nama sementara; diganti setelah tanggal di dalam file diketahui
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, "unduhan_" + System.nanoTime() + ".csv");
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime != null && !mime.isEmpty() ? mime : "text/csv");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Tidak bisa membuat file");
        try {
            byte[] head = new byte[PEEK], tail = new byte[0];
            int headLen = 0;
            long done = 0;
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("Tidak bisa menulis file");
                byte[] buf = new byte[16384];
                for (int r; (r = in.read(buf)) > 0; ) {
                    if (cancel != null && cancel.get()) throw new IOException("Dibatalkan");
                    out.write(buf, 0, r);
                    if (headLen < PEEK) {
                        int n = Math.min(r, PEEK - headLen);
                        System.arraycopy(buf, 0, head, headLen, n);
                        headLen += n;
                    }
                    tail = keepLast(tail, buf, r);
                    done += r;
                    if (progress != null) progress.on(done);
                }
            }
            if (len >= 0 && done != len) throw new IOException("File terpotong");

            String name = Downloader.safeName(baseName);
            if (name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                String first = firstDate(new String(head, 0, headLen, StandardCharsets.ISO_8859_1));
                String last = lastDate(new String(tail, StandardCharsets.ISO_8859_1));
                if (first == null) first = last;
                if (first == null) { first = fallbackDate; last = null; }
                name = datedName(name, first, last);
            }
            cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{dir, name});  // timpa unduhan lama dengan nama yang sama
            v.clear();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            Result res = new Result(uri, name);
            uri = null;
            return res;
        } finally {
            if (uri != null) cr.delete(uri, null, null);  // buang file setengah jadi
        }
    }

    private static byte[] keepLast(byte[] prev, byte[] buf, int r) {
        int total = Math.min(PEEK, prev.length + r);
        byte[] out = new byte[total];
        int fromBuf = Math.min(r, total), fromPrev = total - fromBuf;
        System.arraycopy(prev, prev.length - fromPrev, out, 0, fromPrev);
        System.arraycopy(buf, r - fromBuf, out, fromPrev, fromBuf);
        return out;
    }

    /** Tanggal pertama di teks, sebagai yyyy-MM-dd, atau null. */
    static String firstDate(String text) {
        String best = null;
        int bestAt = Integer.MAX_VALUE;
        for (Pattern p : new Pattern[]{YMD, DMY}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String d = normalize(p, m);
                if (d != null) {
                    if (m.start() < bestAt) { best = d; bestAt = m.start(); }
                    break;
                }
            }
        }
        return best;
    }

    /** Tanggal terakhir di teks, sebagai yyyy-MM-dd, atau null. */
    static String lastDate(String text) {
        String best = null;
        int bestAt = -1;
        for (Pattern p : new Pattern[]{YMD, DMY}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String d = normalize(p, m);
                if (d != null && m.start() > bestAt) { best = d; bestAt = m.start(); }
            }
        }
        return best;
    }

    private static String normalize(Pattern p, Matcher m) {
        int y, mo, d;
        if (p == YMD) {
            y = Integer.parseInt(m.group(1)); mo = Integer.parseInt(m.group(2)); d = Integer.parseInt(m.group(3));
        } else {
            d = Integer.parseInt(m.group(1)); mo = Integer.parseInt(m.group(2)); y = Integer.parseInt(m.group(3));
        }
        if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
        return String.format(Locale.US, "%04d-%02d-%02d", y, mo, d);
    }

    /** data.csv + 2026-10-01 + 2026-10-31 -> data_2026-10-01_2026-10-31.csv */
    static String datedName(String name, String first, String last) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        String dates = first + (last != null && !last.equals(first) ? "_" + last : "");
        return stem + "_" + dates + ext;
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
