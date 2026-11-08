package com.esphub;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Simpan sebagai peta baru": peta yang tampil + titik buatan pengguna.
 *  - Ada peta PDF  -> GeoPDF baru: gambar PDF + lapisan vektor + titik digambar di atasnya, dengan koordinat (ISO 32000).
 *  - Tanpa PDF     -> GeoJSON baru: semua bentuk dari lapisan GeoJSON/KML + titik.
 * Hasilnya bisa dibuka lagi di aplikasi ini, di HP lain, QGIS, Avenza, atau Google Earth (GeoJSON).
 */
final class MapExport {

    private MapExport() { }

    static final int PIN_COLOR = 0xFFF97316;   // oranye, sama untuk layar dan file

    static final class Result {
        byte[] data;
        String ext, mime;
        int outside;       // titik di luar area peta PDF (tidak ikut tergambar)
    }

    static Result export(List<GeoFile.Layer> visible, List<MapView.Pin> pins) throws IOException {
        GeoFile.Layer pdf = null;
        for (GeoFile.Layer l : visible) if (l.image != null && !l.image.isRecycled()) pdf = l;  // paling atas
        return pdf != null ? geoPdf(pdf, visible, pins) : geoJson(visible, pins);
    }

    // ------------------------------------------------------------------ GeoJSON

    static Result geoJson(List<GeoFile.Layer> layers, List<MapView.Pin> pins) throws IOException {
        try {
            JSONArray feats = new JSONArray();
            for (GeoFile.Layer l : layers) {
                for (GeoFile.Shape s : l.shapes) feats.put(feature(s));
            }
            for (MapView.Pin p : pins) {
                JSONObject props = new JSONObject()
                        .put("name", p.name)
                        .put("lat", Math.round(p.lat * 1e7) / 1e7)
                        .put("lon", Math.round(p.lon * 1e7) / 1e7)
                        .put("marker-color", hex(PIN_COLOR))
                        .put("dibuat", iso(p.time));
                if (p.acc > 0) props.put("akurasi_m", Math.round(p.acc * 10) / 10.0);
                feats.put(new JSONObject().put("type", "Feature").put("properties", props)
                        .put("geometry", new JSONObject().put("type", "Point").put("coordinates", pos(p.lon, p.lat))));
            }
            JSONObject fc = new JSONObject().put("type", "FeatureCollection").put("features", feats);
            Result r = new Result();
            r.data = fc.toString(1).getBytes(StandardCharsets.UTF_8);
            r.ext = ".geojson";
            r.mime = "application/geo+json";
            return r;
        } catch (JSONException e) {
            throw new IOException("Gagal membuat GeoJSON: " + e.getMessage());
        }
    }

    private static JSONObject feature(GeoFile.Shape s) throws JSONException {
        JSONObject props = new JSONObject();
        if (!s.name.isEmpty()) props.put("name", s.name);
        if (s.stroke != 0) {
            props.put("stroke", hex(s.stroke));
            props.put("stroke-opacity", round2((s.stroke >>> 24) / 255.0));
            if (s.kind == GeoFile.POINT) props.put("marker-color", hex(s.stroke));
        }
        if (s.kind == GeoFile.POLY) {
            if (s.noFill) props.put("fill-opacity", 0);
            else if (s.fill != 0) {
                props.put("fill", hex(s.fill));
                props.put("fill-opacity", round2((s.fill >>> 24) / 255.0));
            }
        }
        JSONObject g = new JSONObject();
        if (s.kind == GeoFile.POINT) {
            g.put("type", "Point").put("coordinates", pos(s.parts[0][0], s.parts[0][1]));
        } else if (s.kind == GeoFile.LINE) {
            g.put("type", "LineString").put("coordinates", line(s.parts[0], false));
        } else {
            JSONArray rings = new JSONArray();
            for (double[] part : s.parts) rings.put(line(part, true));
            g.put("type", "Polygon").put("coordinates", rings);
        }
        return new JSONObject().put("type", "Feature").put("properties", props).put("geometry", g);
    }

    private static JSONArray line(double[] p, boolean close) throws JSONException {
        JSONArray a = new JSONArray();
        for (int i = 0; i + 1 < p.length; i += 2) a.put(pos(p[i], p[i + 1]));
        if (close && p.length >= 4 && (p[0] != p[p.length - 2] || p[1] != p[p.length - 1])) a.put(pos(p[0], p[1]));
        return a;
    }

    private static JSONArray pos(double lon, double lat) throws JSONException {
        return new JSONArray().put(Math.round(lon * 1e7) / 1e7).put(Math.round(lat * 1e7) / 1e7);
    }

    private static String hex(int argb) { return String.format(Locale.US, "#%06x", argb & 0xffffff); }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    static String iso(long t) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date(t));
    }

    // ------------------------------------------------------------------ GeoPDF

    static Result geoPdf(GeoFile.Layer base, List<GeoFile.Layer> layers, List<MapView.Pin> pins) throws IOException {
        Bitmap src = base.image;
        Bitmap bmp;
        try {
            bmp = src.copy(Bitmap.Config.ARGB_8888, true);
        } catch (OutOfMemoryError e) {
            bmp = null;
        }
        if (bmp == null) throw new IOException("Memori HP tidak cukup untuk menyimpan peta PDF");
        int w = bmp.getWidth(), h = bmp.getHeight();
        double[] a = base.imageToMerc;
        double det = a[0] * a[4] - a[1] * a[3];
        if (Math.abs(det) < 1e-15) throw new IOException("Koordinat peta PDF tidak valid");

        Canvas c = new Canvas(bmp);
        float unit = Math.max(w, h) / 1000f;  // ±4 px pada gambar 4096 px
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(4.5f * unit);
        Paint halo = new Paint(text);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(1.2f * unit);
        halo.setColor(Color.WHITE);
        text.setColor(0xFF111111);
        Path path = new Path();

        // lapisan vektor yang tampil (selain gambar PDF dasar) ikut digambar
        for (GeoFile.Layer l : layers) {
            if (l == base) continue;
            for (int pass = 0; pass < 3; pass++) {
                int kind = pass == 0 ? GeoFile.POLY : pass == 1 ? GeoFile.LINE : GeoFile.POINT;
                for (GeoFile.Shape s : l.shapes) {
                    if (s.kind != kind) continue;
                    int sc = s.stroke != 0 ? s.stroke : 0xFFFFFFFF;
                    if (kind == GeoFile.POINT) {
                        float[] p = px(a, det, s.parts[0][0], s.parts[0][1]);
                        fill.setColor(sc | 0xff000000);
                        c.drawCircle(p[0], p[1], 2 * unit, fill);
                        if (!s.name.isEmpty()) {
                            c.drawText(s.name, p[0], p[1] - 3.5f * unit, halo);
                            c.drawText(s.name, p[0], p[1] - 3.5f * unit, text);
                        }
                        continue;
                    }
                    path.rewind();
                    for (double[] part : s.parts) {
                        for (int i = 0; i + 1 < part.length; i += 2) {
                            float[] p = px(a, det, part[i], part[i + 1]);
                            if (i == 0) path.moveTo(p[0], p[1]); else path.lineTo(p[0], p[1]);
                        }
                        if (kind == GeoFile.POLY) path.close();
                    }
                    if (kind == GeoFile.POLY && !s.noFill) {
                        path.setFillType(Path.FillType.EVEN_ODD);
                        fill.setColor(s.fill != 0 ? s.fill : (sc & 0x00ffffff) | 0x33000000);
                        c.drawPath(path, fill);
                    }
                    stroke.setColor(sc);
                    stroke.setStrokeWidth(kind == GeoFile.POLY ? 1.2f * unit : 1.8f * unit);
                    c.drawPath(path, stroke);
                }
            }
        }

        int outside = 0;
        for (MapView.Pin p : pins) {
            float[] q = px(a, det, p.lon, p.lat);
            if (q[0] < 0 || q[1] < 0 || q[0] > w || q[1] > h) { outside++; continue; }
            fill.setColor(Color.WHITE);
            c.drawCircle(q[0], q[1], 3 * unit, fill);
            fill.setColor(PIN_COLOR);
            c.drawCircle(q[0], q[1], 2.2f * unit, fill);
            if (!p.name.isEmpty()) {
                c.drawText(p.name, q[0], q[1] - 4.5f * unit, halo);
                c.drawText(p.name, q[0], q[1] - 4.5f * unit, text);
            }
        }

        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, jpg);
        bmp.recycle();

        // sudut gambar -> lintang/bujur (gambar linear di Mercator, jadi 4 sudut sudah tepat)
        double[][] corners = {{0, h}, {0, 0}, {w, 0}, {w, h}};  // LPTS: (0,0) (0,1) (1,1) (1,0)
        StringBuilder gpts = new StringBuilder();
        for (double[] k : corners) {
            double mx = a[0] * k[0] + a[1] * k[1] + a[2], my = a[3] * k[0] + a[4] * k[1] + a[5];
            double lon = Math.toDegrees(mx / MapView.EARTH);
            double lat = Math.toDegrees(2 * Math.atan(Math.exp(my / MapView.EARTH)) - Math.PI / 2);
            gpts.append(String.format(Locale.US, "%.10f %.10f ", lat, lon));
        }
        Result r = new Result();
        r.data = writePdf(jpg.toByteArray(), w, h, gpts.toString().trim());
        r.ext = ".pdf";
        r.mime = "application/pdf";
        r.outside = outside;
        return r;
    }

    /** lon/lat -> piksel gambar (kebalikan imageToMerc). */
    private static float[] px(double[] a, double det, double lon, double lat) {
        double dx = MapView.mx(lon) - a[2], dy = MapView.my(lat) - a[5];
        return new float[]{(float) ((a[4] * dx - a[1] * dy) / det), (float) ((-a[3] * dx + a[0] * dy) / det)};
    }

    /** PDF satu halaman: gambar JPEG penuh + koordinat GeoPDF (ISO 32000, WGS84). */
    static byte[] writePdf(byte[] jpeg, int w, int h, String gpts) throws IOException {
        double pw = w * 72.0 / 200, ph = h * 72.0 / 200;   // 200 dpi
        String size = String.format(Locale.US, "%.2f %.2f", pw, ph);
        String wkt = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + 4096);
        int[] off = new int[6];
        w(out, "%PDF-1.7\n%âãÏÓ\n");
        off[1] = out.size();
        w(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        off[2] = out.size();
        w(out, "2 0 obj\n<< /Type /Pages /Kids [ 3 0 R ] /Count 1 >>\nendobj\n");
        off[3] = out.size();
        w(out, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 " + size + " ]\n"
                + "/Resources << /XObject << /Im0 4 0 R >> /ProcSet [ /PDF /ImageC ] >>\n"
                + "/Contents 5 0 R\n"
                + "/VP [ << /Type /Viewport /BBox [ 0 0 " + size + " ] /Name (Peta)\n"
                + "  /Measure << /Type /Measure /Subtype /GEO /Bounds [ 0 0 0 1 1 1 1 0 ]\n"
                + "    /GPTS [ " + gpts + " ]\n"
                + "    /LPTS [ 0 0 0 1 1 1 1 0 ]\n"
                + "    /GCS << /Type /GEOGCS /EPSG 4326 /WKT (" + wkt + ") >> >> >> ]\n"
                + ">>\nendobj\n");
        off[4] = out.size();
        w(out, "4 0 obj\n<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpeg.length + " >>\nstream\n");
        out.write(jpeg);
        w(out, "\nendstream\nendobj\n");
        off[5] = out.size();
        String content = "q " + String.format(Locale.US, "%.2f 0 0 %.2f 0 0", pw, ph) + " cm /Im0 Do Q";
        w(out, "5 0 obj\n<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream\nendobj\n");
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 6\n0000000000 65535 f \n");
        for (int i = 1; i <= 5; i++) x.append(String.format(Locale.US, "%010d 00000 n \n", off[i]));
        x.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        w(out, x.toString());
        return out.toByteArray();
    }

    private static void w(ByteArrayOutputStream o, String s) throws IOException {
        o.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    // ------------------------------------------------------------------ Download

    /** Salin ke Download/ESP32Hub/Peta/. Mengembalikan nama file yang dipakai. */
    static String saveToDownloads(Context ctx, String name, String mime, byte[] data) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String dir = Downloader.FOLDER + "Peta/";
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Tidak bisa membuat file di Download");
        try {
            try (OutputStream o = cr.openOutputStream(uri)) {
                if (o == null) throw new IOException("Tidak bisa menulis file");
                o.write(data);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            Uri done = uri;
            uri = null;
            try (android.database.Cursor q = cr.query(done, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (q != null && q.moveToFirst() && q.getString(0) != null) return q.getString(0);  // Android bisa menambah " (1)"
            }
            return name;
        } finally {
            if (uri != null) cr.delete(uri, null, null);
        }
    }
}
