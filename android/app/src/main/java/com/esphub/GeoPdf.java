package com.esphub;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Membaca peta GeoPDF (PDF yang menyimpan koordinat) menjadi lapisan gambar.
 *
 * Koordinat dibaca dari:
 *  - ISO 32000 / Adobe: halaman -> /VP [ << /BBox /Measure << /Subtype /GEO /GPTS /LPTS >> >> ]
 *    (QGIS, GDAL, ArcGIS, Avenza, dsb.)
 *  - OGC / TerraGo: halaman -> /LGIDict << /Registration atau /CTM, /Projection (UTM atau geografis) >>
 * Isi halaman digambar dengan PdfRenderer bawaan Android, jadi tidak butuh library atau internet.
 */
final class GeoPdf {

    private GeoPdf() { }

    // sisi terpanjang gambar: 4096 aman untuk tekstur GPU hampir semua HP; turun bila memori tidak cukup
    private static final int[] MAX_PX = {4096, 2560, 1600};

    static boolean isPdf(byte[] d) {
        int n = Math.min(d.length - 4, 1024);
        for (int i = 0; i < n; i++) {
            if (d[i] == '%' && d[i + 1] == 'P' && d[i + 2] == 'D' && d[i + 3] == 'F') return true;
        }
        return false;
    }

    /** file = salinan PDF di penyimpanan aplikasi (PdfRenderer butuh file yang bisa di-seek). */
    static GeoFile.Layer read(String name, File file, byte[] data) throws IOException {
        Doc doc = new Doc(data);
        List<PageInfo> pages = doc.pages();
        if (pages.isEmpty()) throw new IOException("PDF tidak berisi halaman yang bisa dibaca");

        Geo geo = null;
        int pageIndex = -1;
        String why = null;
        for (int i = 0; i < pages.size() && geo == null; i++) {
            try {
                geo = georef(doc, pages.get(i));
                if (geo != null) pageIndex = i;
            } catch (IOException e) {
                if (why == null) why = e.getMessage();
            }
        }
        if (geo == null) {
            throw new IOException(why != null ? why
                    : "PDF ini tidak punya koordinat peta (bukan GeoPDF). Ekspor ulang dari QGIS/ArcGIS/Avenza sebagai GeoPDF.");
        }
        PageInfo page = pages.get(pageIndex);
        if (page.rotate % 360 != 0) throw new IOException("Halaman PDF diputar (" + page.rotate + "°) belum didukung");

        // area yang digambar = bingkai peta (BBox viewport / neatline), dipotong ke halaman
        double[] box = page.box;
        double rx0 = Math.max(box[0], geo.clip[0]), ry0 = Math.max(box[1], geo.clip[1]);
        double rx1 = Math.min(box[2], geo.clip[2]), ry1 = Math.min(box[3], geo.clip[3]);
        if (rx1 - rx0 < 1 || ry1 - ry0 < 1) { rx0 = box[0]; ry0 = box[1]; rx1 = box[2]; ry1 = box[3]; }
        Bitmap bmp = null;
        double k = 1;
        int w = 1, h = 1;
        for (int max : MAX_PX) {
            k = Math.min(4.0, max / Math.max(rx1 - rx0, ry1 - ry0));
            w = Math.max(1, (int) Math.round((rx1 - rx0) * k));
            h = Math.max(1, (int) Math.round((ry1 - ry0) * k));
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                break;
            } catch (OutOfMemoryError e) {
                bmp = null;
            }
        }
        if (bmp == null) throw new IOException("Memori HP tidak cukup untuk menggambar PDF ini");
        bmp.eraseColor(Color.WHITE);
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(fd);
             PdfRenderer.Page p = r.openPage(pageIndex)) {
            // PdfRenderer: titik (0,0) = pojok kiri atas kotak halaman, sumbu y ke bawah
            Matrix m = new Matrix();
            m.setTranslate((float) -(rx0 - box[0]), (float) -(box[3] - ry1));
            m.postScale((float) k, (float) k);
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        } catch (SecurityException e) {
            throw new IOException("PDF terkunci kata sandi");
        } catch (IOException | RuntimeException e) {
            throw new IOException("PDF tidak bisa digambar: " + e.getMessage());
        }

        // piksel (ix, iy) -> titik halaman (ux, uy) -> meter Mercator
        double[] a = geo.toMerc;
        double ux0 = rx0, uy0 = ry1, s = 1 / k;
        // ux = ux0 + ix*s ; uy = uy0 - iy*s
        double[] img = {
                a[0] * s, -a[1] * s, a[0] * ux0 + a[1] * uy0 + a[2],
                a[3] * s, -a[4] * s, a[3] * ux0 + a[4] * uy0 + a[5]};

        GeoFile.Layer layer = new GeoFile.Layer();
        layer.name = name;
        layer.image = bmp;
        layer.isPdf = true;
        layer.imageToMerc = img;
        for (double[] c : new double[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) {
            double mx = img[0] * c[0] + img[1] * c[1] + img[2], my = img[3] * c[0] + img[4] * c[1] + img[5];
            double lon = Math.toDegrees(mx / MapView.EARTH);
            double lat = Math.toDegrees(2 * Math.atan(Math.exp(my / MapView.EARTH)) - Math.PI / 2);
            layer.minLon = Math.min(layer.minLon, lon); layer.maxLon = Math.max(layer.maxLon, lon);
            layer.minLat = Math.min(layer.minLat, lat); layer.maxLat = Math.max(layer.maxLat, lat);
        }
        if (layer.minLon < -180 || layer.maxLon > 180 || layer.minLat < -86 || layer.maxLat > 86) {
            bmp.recycle();
            throw new IOException("Koordinat di PDF tidak masuk akal (di luar batas bumi)");
        }
        layer.note = geo.note;
        return layer;
    }

    // ------------------------------------------------------------------ georeferensi

    private static final class Geo {
        double[] toMerc;   // affine titik halaman -> meter Mercator: mx = a0*ux + a1*uy + a2, my = a3*ux + a4*uy + a5
        double[] clip;     // [x0, y0, x1, y1] area peta di halaman
        String note = "";
    }

    private static Geo georef(Doc doc, PageInfo page) throws IOException {
        Geo g = iso32000(doc, page);
        if (g == null) g = lgi(doc, page);
        return g;
    }

    /** ISO 32000: pilih viewport GEO terluas (bingkai peta utama, bukan peta inset). */
    private static Geo iso32000(Doc doc, PageInfo page) throws IOException {
        Object vpObj = doc.resolve(page.dict.get("VP"));
        if (!(vpObj instanceof List)) return null;
        Geo best = null;
        double bestArea = -1;
        for (Object o : (List<?>) vpObj) {
            Object vo = doc.resolve(o);
            if (!(vo instanceof Map)) continue;
            Map<?, ?> vp = (Map<?, ?>) vo;
            Object mo = doc.resolve(vp.get("Measure"));
            if (!(mo instanceof Map)) continue;
            Map<?, ?> me = (Map<?, ?>) mo;
            if (!"GEO".equals(nameOf(doc.resolve(me.get("Subtype"))))) continue;
            double[] bbox = nums(doc, vp.get("BBox"));
            double[] gpts = nums(doc, me.get("GPTS"));
            double[] lpts = nums(doc, me.get("LPTS"));
            if (lpts == null) lpts = nums(doc, me.get("Bounds"));
            if (bbox == null || bbox.length < 4 || gpts == null || lpts == null) continue;
            int n = Math.min(gpts.length, lpts.length) / 2;
            if (n < 2) continue;
            double x0 = Math.min(bbox[0], bbox[2]), x1 = Math.max(bbox[0], bbox[2]);
            double y0 = Math.min(bbox[1], bbox[3]), y1 = Math.max(bbox[1], bbox[3]);
            // GPTS = lintang, bujur (beberapa pembuat PDF terbalik: deteksi dari nilainya)
            boolean swap = false;
            for (int i = 0; i < n; i++) if (Math.abs(gpts[2 * i]) > 90) swap = true;
            double[][] src = new double[n][], dst = new double[n][];
            for (int i = 0; i < n; i++) {
                double ux = x0 + lpts[2 * i] * (x1 - x0), uy = y0 + lpts[2 * i + 1] * (y1 - y0);
                double lat = swap ? gpts[2 * i + 1] : gpts[2 * i], lon = swap ? gpts[2 * i] : gpts[2 * i + 1];
                src[i] = new double[]{ux, uy};
                dst[i] = new double[]{MapView.mx(lon), MapView.my(lat)};
            }
            double area = (x1 - x0) * (y1 - y0);
            if (area <= bestArea) continue;
            Geo g = new Geo();
            g.toMerc = fit(src, dst);
            g.clip = new double[]{x0, y0, x1, y1};
            Object gcs = doc.resolve(me.get("GCS"));
            if (gcs instanceof Map) {
                Object wkt = doc.resolve(((Map<?, ?>) gcs).get("WKT"));
                if (wkt instanceof String && !((String) wkt).contains("WGS") && !((String) wkt).contains("4326")) {
                    g.note = "datum bukan WGS84, posisi bisa bergeser beberapa ratus meter";
                }
            }
            best = g;
            bestArea = area;
        }
        return best;
    }

    /** OGC/TerraGo LGIDict: /Registration [[x y X Y] ...] atau /CTM, proyeksi UTM atau geografis. */
    private static Geo lgi(Doc doc, PageInfo page) throws IOException {
        Object lo = doc.resolve(page.dict.get("LGIDict"));
        List<Object> dicts = new ArrayList<>();
        if (lo instanceof Map) dicts.add(lo);
        else if (lo instanceof List) for (Object o : (List<?>) lo) dicts.add(doc.resolve(o));
        for (Object d0 : dicts) {
            if (!(d0 instanceof Map)) continue;
            Map<?, ?> d = (Map<?, ?>) d0;
            Object po = doc.resolve(d.get("Projection"));
            if (!(po instanceof Map)) continue;
            Map<?, ?> proj = (Map<?, ?>) po;
            String type = str(doc.resolve(proj.get("ProjectionType"))).toUpperCase(Locale.ROOT);
            int zone = (int) num(doc.resolve(proj.get("Zone")), 0);
            boolean south = str(doc.resolve(proj.get("Hemisphere"))).toUpperCase(Locale.ROOT).startsWith("S");
            boolean geographic = type.startsWith("GEO") || type.equals("LL");
            boolean utm = type.equals("UT");
            if (!geographic && !(utm && zone >= 1 && zone <= 60)) {
                throw new IOException("Proyeksi GeoPDF \"" + type + "\" belum didukung. Ekspor ulang sebagai GeoPDF ISO 32000 (bawaan QGIS/GDAL), atau UTM.");
            }
            double[] box = page.box;
            double[] clip = box;
            double[] neat = nums(doc, d.get("Neatline"));
            if (neat != null && neat.length >= 6) {
                double a = Double.MAX_VALUE, b = Double.MAX_VALUE, c = -Double.MAX_VALUE, e = -Double.MAX_VALUE;
                for (int i = 0; i + 1 < neat.length; i += 2) {
                    a = Math.min(a, neat[i]); c = Math.max(c, neat[i]);
                    b = Math.min(b, neat[i + 1]); e = Math.max(e, neat[i + 1]);
                }
                clip = new double[]{a, b, c, e};
            }
            List<double[]> src = new ArrayList<>(), dst = new ArrayList<>();
            Object reg = doc.resolve(d.get("Registration"));
            double[] ctm = nums(doc, d.get("CTM"));
            if (reg instanceof List && ((List<?>) reg).size() >= 2) {
                for (Object r : (List<?>) reg) {
                    double[] v = nums(doc, r);
                    if (v == null || v.length < 4) continue;
                    src.add(new double[]{v[0], v[1]});
                    dst.add(projToMerc(v[2], v[3], geographic, zone, south));
                }
            } else if (ctm != null && ctm.length >= 6) {
                for (double[] c : new double[][]{{clip[0], clip[1]}, {clip[2], clip[1]}, {clip[0], clip[3]}, {clip[2], clip[3]}}) {
                    double X = ctm[0] * c[0] + ctm[2] * c[1] + ctm[4], Y = ctm[1] * c[0] + ctm[3] * c[1] + ctm[5];
                    src.add(c);
                    dst.add(projToMerc(X, Y, geographic, zone, south));
                }
            }
            if (src.size() < 2) continue;
            Geo g = new Geo();
            g.toMerc = fit(src.toArray(new double[0][]), dst.toArray(new double[0][]));
            g.clip = clip;
            String datum = str(doc.resolve(proj.get("Datum")));
            if (!datum.isEmpty() && !datum.toUpperCase(Locale.ROOT).startsWith("WE") && !datum.toUpperCase(Locale.ROOT).contains("WGS")) {
                g.note = "datum " + datum + " dianggap WGS84, posisi bisa bergeser";
            }
            return g;
        }
        return null;
    }

    private static double[] projToMerc(double x, double y, boolean geographic, int zone, boolean south) {
        double lon, lat;
        if (geographic) { lon = x; lat = y; }
        else { double[] ll = utmToLonLat(x, y, zone, south); lon = ll[0]; lat = ll[1]; }
        return new double[]{MapView.mx(lon), MapView.my(lat)};
    }

    /** UTM (WGS84) -> lon/lat derajat. */
    static double[] utmToLonLat(double easting, double northing, int zone, boolean south) {
        double a = 6378137.0, f = 1 / 298.257223563, k0 = 0.9996;
        double e2 = f * (2 - f), ep2 = e2 / (1 - e2);
        double x = easting - 500000, y = south ? northing - 10000000 : northing;
        double m = y / k0;
        double mu = m / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256));
        double e1 = (1 - Math.sqrt(1 - e2)) / (1 + Math.sqrt(1 - e2));
        double phi1 = mu + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
                + (21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
                + (151 * Math.pow(e1, 3) / 96) * Math.sin(6 * mu)
                + (1097 * Math.pow(e1, 4) / 512) * Math.sin(8 * mu);
        double s = Math.sin(phi1), c = Math.cos(phi1), t = Math.tan(phi1);
        double n1 = a / Math.sqrt(1 - e2 * s * s);
        double t1 = t * t, c1 = ep2 * c * c;
        double r1 = a * (1 - e2) / Math.pow(1 - e2 * s * s, 1.5);
        double d = x / (n1 * k0);
        double lat = phi1 - (n1 * t / r1) * (d * d / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * Math.pow(d, 4) / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * Math.pow(d, 6) / 720);
        double lon = (d - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * Math.pow(d, 5) / 120) / c;
        return new double[]{(zone - 1) * 6 - 180 + 3 + Math.toDegrees(lon), Math.toDegrees(lat)};
    }

    /** Affine kuadrat terkecil dari titik halaman ke meter Mercator (2 titik: tanpa putar/geser miring). */
    static double[] fit(double[][] src, double[][] dst) throws IOException {
        int n = src.length;
        if (n == 2) {
            double dx = src[1][0] - src[0][0], dy = src[1][1] - src[0][1];
            if (Math.abs(dx) < 1e-9 || Math.abs(dy) < 1e-9) throw new IOException("Titik ikat GeoPDF tidak cukup");
            double sx = (dst[1][0] - dst[0][0]) / dx, sy = (dst[1][1] - dst[0][1]) / dy;
            return new double[]{sx, 0, dst[0][0] - sx * src[0][0], 0, sy, dst[0][1] - sy * src[0][1]};
        }
        // pusatkan supaya hitungan stabil
        double cx = 0, cy = 0;
        for (double[] p : src) { cx += p[0]; cy += p[1]; }
        cx /= n; cy /= n;
        double sxx = 0, sxy = 0, syy = 0, sx1 = 0, sy1 = 0;
        double[] bx = new double[3], by = new double[3];
        for (int i = 0; i < n; i++) {
            double x = src[i][0] - cx, y = src[i][1] - cy;
            sxx += x * x; sxy += x * y; syy += y * y; sx1 += x; sy1 += y;
            bx[0] += x * dst[i][0]; bx[1] += y * dst[i][0]; bx[2] += dst[i][0];
            by[0] += x * dst[i][1]; by[1] += y * dst[i][1]; by[2] += dst[i][1];
        }
        double[][] m = {{sxx, sxy, sx1}, {sxy, syy, sy1}, {sx1, sy1, n}};
        double[] px = solve3(m, bx), py = solve3(m, by);
        if (px == null || py == null) throw new IOException("Titik ikat GeoPDF segaris, tidak bisa dipakai");
        // kembalikan dari koordinat terpusat
        return new double[]{px[0], px[1], px[2] - px[0] * cx - px[1] * cy,
                py[0], py[1], py[2] - py[0] * cx - py[1] * cy};
    }

    private static double[] solve3(double[][] a, double[] b) {
        double det = det3(a);
        if (Math.abs(det) < 1e-12) return null;
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] t = {a[0].clone(), a[1].clone(), a[2].clone()};
            for (int r = 0; r < 3; r++) t[r][c] = b[r];
            out[c] = det3(t) / det;
        }
        return out;
    }

    private static double det3(double[][] a) {
        return a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
    }

    // ------------------------------------------------------------------ pembantu nilai

    private static String nameOf(Object o) { return o instanceof Name ? ((Name) o).s : ""; }

    private static String str(Object o) {
        if (o instanceof Name) return ((Name) o).s;
        if (o instanceof String) return ((String) o).trim();
        return "";
    }

    private static double num(Object o, double def) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    /** Larik angka (angka boleh ditulis sebagai string, seperti di LGIDict). */
    private static double[] nums(Doc doc, Object o) {
        o = doc.resolve(o);
        if (!(o instanceof List)) return null;
        List<?> l = (List<?>) o;
        double[] out = new double[l.size()];
        for (int i = 0; i < l.size(); i++) {
            double v = num(doc.resolve(l.get(i)), Double.NaN);
            if (Double.isNaN(v)) return null;
            out[i] = v;
        }
        return out;
    }

    // ------------------------------------------------------------------ pembaca struktur PDF (kecil)

    private static final class Name {
        final String s;
        Name(String s) { this.s = s; }
    }

    private static final class Ref {
        final int num;
        Ref(int num) { this.num = num; }
    }

    private static final Object NULL = new Object();

    private static final class PageInfo {
        Map<?, ?> dict;
        double[] box;   // CropBox (atau MediaBox), dinormalkan [x0 y0 x1 y1]
        int rotate;
    }

    /** Membaca semua objek PDF, termasuk yang dipadatkan di object stream (PDF 1.5+). */
    private static final class Doc {
        final String s;
        final Map<Integer, Object> objs = new HashMap<>();
        Object root;

        Doc(byte[] data) throws IOException {
            s = new String(data, StandardCharsets.ISO_8859_1);
            Pattern objRe = Pattern.compile("(\\d+)\\s+(\\d+)\\s+obj\\b");
            Matcher m = objRe.matcher(s);
            int pos = 0;
            while (pos < s.length() && m.find(pos)) {
                int num = Integer.parseInt(m.group(1));
                Parser p = new Parser(s, m.end());
                Object o;
                try {
                    o = p.value();
                } catch (RuntimeException e) {
                    pos = m.end();
                    continue;
                }
                pos = p.i;
                if (o instanceof Map) {
                    Map<?, ?> d = (Map<?, ?>) o;
                    int ds = p.streamStart();
                    if (ds >= 0) {
                        int de = streamEnd(d, ds);
                        pos = de;
                        String type = nameOf(d.get("Type"));
                        if (type.equals("ObjStm")) {
                            try { readObjStm(d, inflate(ds, de)); } catch (IOException | RuntimeException ignored) { }
                        } else if (type.equals("XRef") && d.get("Root") != null) {
                            root = d.get("Root");
                        }
                    }
                }
                objs.put(num, o);  // objek yang muncul belakangan (pembaruan) menimpa yang lama
            }
            int t = s.lastIndexOf("trailer");
            if (t >= 0) {
                try {
                    Object tr = new Parser(s, t + 7).value();
                    if (tr instanceof Map && ((Map<?, ?>) tr).get("Root") != null) root = ((Map<?, ?>) tr).get("Root");
                } catch (RuntimeException ignored) { }
            }
            if (root == null || !(resolve(root) instanceof Map)) {
                for (Object o : objs.values()) {
                    if (o instanceof Map && "Catalog".equals(nameOf(((Map<?, ?>) o).get("Type")))) { root = o; break; }
                }
            }
            if (root == null) throw new IOException("Struktur PDF tidak dikenali (Catalog tidak ditemukan)");
        }

        private int streamEnd(Map<?, ?> d, int ds) {
            Object len = d.get("Length");
            if (len instanceof Double) {
                int e = ds + (int) (double) (Double) len;
                if (e > ds && e <= s.length()) {
                    int k = s.indexOf("endstream", e);
                    if (k >= 0 && k - e < 32) return e;
                }
            }
            int k = s.indexOf("endstream", ds);
            if (k < 0) return s.length();
            int e = k;
            if (e > ds && s.charAt(e - 1) == '\n') e--;
            if (e > ds && s.charAt(e - 1) == '\r') e--;
            return e;
        }

        private String inflate(int from, int to) throws IOException {
            Inflater inf = new Inflater();
            byte[] in = s.substring(from, to).getBytes(StandardCharsets.ISO_8859_1);
            inf.setInput(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try {
                while (!inf.finished()) {
                    int r = inf.inflate(buf);
                    if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    out.write(buf, 0, r);
                    if (out.size() > 16 * 1024 * 1024) break;
                }
            } catch (DataFormatException e) {
                throw new IOException("stream rusak");
            } finally {
                inf.end();
            }
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        private void readObjStm(Map<?, ?> d, String data) {
            int n = (int) num(d.get("N"), 0), first = (int) num(d.get("First"), 0);
            Parser hp = new Parser(data, 0);
            int[] nums = new int[n], offs = new int[n];
            for (int i = 0; i < n; i++) {
                nums[i] = (int) num(hp.value(), -1);
                offs[i] = (int) num(hp.value(), -1);
            }
            for (int i = 0; i < n; i++) {
                if (nums[i] < 0 || offs[i] < 0 || first + offs[i] >= data.length()) continue;
                try {
                    objs.put(nums[i], new Parser(data, first + offs[i]).value());
                } catch (RuntimeException ignored) { }
            }
        }

        Object resolve(Object o) {
            for (int guard = 0; o instanceof Ref && guard < 32; guard++) o = objs.get(((Ref) o).num);
            return o == NULL ? null : o;
        }

        List<PageInfo> pages() {
            List<PageInfo> out = new ArrayList<>();
            Object cat = resolve(root);
            if (!(cat instanceof Map)) return out;
            walk(resolve(((Map<?, ?>) cat).get("Pages")), null, null, 0, out, 0);
            return out;
        }

        private void walk(Object node, double[] media, double[] crop, int rot, List<PageInfo> out, int depth) {
            if (!(node instanceof Map) || depth > 32 || out.size() > 500) return;
            Map<?, ?> d = (Map<?, ?>) node;
            double[] mb = nums(this, d.get("MediaBox"));
            double[] cb = nums(this, d.get("CropBox"));
            if (mb != null && mb.length >= 4) media = mb;
            if (cb != null && cb.length >= 4) crop = cb;
            Object r = resolve(d.get("Rotate"));
            if (r instanceof Double) rot = (int) (double) (Double) r;
            Object kids = resolve(d.get("Kids"));
            if (kids instanceof List) {
                for (Object k : (List<?>) kids) walk(resolve(k), media, crop, rot, out, depth + 1);
                return;
            }
            double[] b = crop != null ? crop : media != null ? media : new double[]{0, 0, 612, 792};
            PageInfo p = new PageInfo();
            p.dict = d;
            p.box = new double[]{Math.min(b[0], b[2]), Math.min(b[1], b[3]), Math.max(b[0], b[2]), Math.max(b[1], b[3])};
            if (crop != null && media != null) {  // CropBox dipotong ke MediaBox
                p.box[0] = Math.max(p.box[0], Math.min(media[0], media[2]));
                p.box[1] = Math.max(p.box[1], Math.min(media[1], media[3]));
                p.box[2] = Math.min(p.box[2], Math.max(media[0], media[2]));
                p.box[3] = Math.min(p.box[3], Math.max(media[1], media[3]));
            }
            p.rotate = ((rot % 360) + 360) % 360;
            out.add(p);
        }
    }

    /** Pengurai sintaks objek PDF: angka, nama, string, larik, kamus, referensi "n g R". */
    private static final class Parser {
        final String s;
        int i;

        Parser(String s, int i) { this.s = s; this.i = i; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '%') { while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++; }
                else if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0) i++;
                else break;
            }
        }

        /** Posisi awal data stream bila objek ini diikuti "stream", -1 bila tidak. */
        int streamStart() {
            ws();
            if (!s.startsWith("stream", i)) return -1;
            int j = i + 6;
            if (j < s.length() && s.charAt(j) == '\r') j++;
            if (j < s.length() && s.charAt(j) == '\n') j++;
            return j;
        }

        static boolean delim(char c) {
            return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0 || c == '(' || c == ')'
                    || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%';
        }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalStateException("eof");
            char c = s.charAt(i);
            if (c == '<' && s.startsWith("<<", i)) {
                i += 2;
                Map<String, Object> d = new LinkedHashMap<>();
                while (true) {
                    ws();
                    if (i >= s.length()) throw new IllegalStateException("eof");
                    if (s.startsWith(">>", i)) { i += 2; return d; }
                    Object k = value();
                    if (!(k instanceof Name)) throw new IllegalStateException("kunci kamus");
                    d.put(((Name) k).s, value());
                }
            }
            if (c == '[') {
                i++;
                List<Object> a = new ArrayList<>();
                while (true) {
                    ws();
                    if (i >= s.length()) throw new IllegalStateException("eof");
                    if (s.charAt(i) == ']') { i++; return a; }
                    a.add(value());
                }
            }
            if (c == '/') {
                int j = ++i;
                while (i < s.length() && !delim(s.charAt(i))) i++;
                String n = s.substring(j, i);
                if (n.indexOf('#') >= 0) n = unhexName(n);
                return new Name(n);
            }
            if (c == '(') return literal();
            if (c == '<') {
                int e = s.indexOf('>', i);
                if (e < 0) throw new IllegalStateException("hex");
                String h = s.substring(i + 1, e).replaceAll("\\s", "");
                i = e + 1;
                if (h.length() % 2 == 1) h += "0";
                byte[] b = new byte[h.length() / 2];
                for (int k = 0; k < b.length; k++) b[k] = (byte) Integer.parseInt(h.substring(2 * k, 2 * k + 2), 16);
                return decodeText(b);
            }
            if (c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                int j = i;
                i++;
                while (i < s.length() && "0123456789.+-eE".indexOf(s.charAt(i)) >= 0) i++;
                String t = s.substring(j, i);
                double v;
                try { v = Double.parseDouble(t); } catch (NumberFormatException e) { v = 0; }
                // "n g R" ?
                if (t.indexOf('.') < 0 && t.indexOf('-') < 0) {
                    int save = i;
                    ws();
                    int g0 = i;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                    if (i > g0) {
                        ws();
                        if (i < s.length() && s.charAt(i) == 'R' && (i + 1 >= s.length() || delim(s.charAt(i + 1)))) {
                            i++;
                            return new Ref((int) v);
                        }
                    }
                    i = save;
                }
                return v;
            }
            // kata kunci
            int j = i;
            while (i < s.length() && !delim(s.charAt(i))) i++;
            String w = s.substring(j, i);
            if (w.equals("true")) return Boolean.TRUE;
            if (w.equals("false")) return Boolean.FALSE;
            if (w.equals("null")) return NULL;
            if (w.isEmpty()) { i++; throw new IllegalStateException("token"); }
            return new Name(w);  // token lain (mis. "obj"), diperlakukan sebagai nama
        }

        private String literal() {
            StringBuilder b = new StringBuilder();
            int depth = 0;
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case '\r': if (i < s.length() && s.charAt(i) == '\n') i++; break;
                        case '\n': break;
                        default:
                            if (e >= '0' && e <= '7') {
                                int v = e - '0';
                                for (int k = 0; k < 2 && i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '7'; k++) {
                                    v = v * 8 + (s.charAt(i++) - '0');
                                }
                                b.append((char) (v & 0xff));
                            } else {
                                b.append(e);
                            }
                    }
                } else if (c == '(') {
                    depth++;
                    b.append(c);
                } else if (c == ')') {
                    if (depth-- == 0) break;
                    b.append(c);
                } else {
                    b.append(c);
                }
            }
            String raw = b.toString();
            return decodeText(raw.getBytes(StandardCharsets.ISO_8859_1));
        }

        private static String decodeText(byte[] b) {
            if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) {
                return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
            }
            return new String(b, StandardCharsets.ISO_8859_1);
        }

        private static String unhexName(String n) {
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < n.length(); k++) {
                char c = n.charAt(k);
                if (c == '#' && k + 2 < n.length()) {
                    try {
                        b.append((char) Integer.parseInt(n.substring(k + 1, k + 3), 16));
                        k += 2;
                        continue;
                    } catch (NumberFormatException ignored) { }
                }
                b.append(c);
            }
            return b.toString();
        }
    }
}
