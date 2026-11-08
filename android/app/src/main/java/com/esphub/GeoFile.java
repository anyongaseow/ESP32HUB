package com.esphub;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Membaca peta dari file GeoJSON, KML, atau KMZ menjadi daftar bentuk (titik, garis, poligon)
 * dengan koordinat lon/lat (WGS84). Hanya memakai pustaka bawaan Android (org.json, XmlPullParser),
 * jadi tidak butuh internet dan tidak menambah library.
 */
final class GeoFile {

    static final int POINT = 0, LINE = 1, POLY = 2;

    /** Satu bentuk. parts: tiap bagian = [lon0, lat0, lon1, lat1, ...]. Poligon: parts[0] luar, sisanya lubang. */
    static final class Shape {
        int kind;
        String name = "";
        double[][] parts;
        int stroke, fill;          // ARGB; 0 = pakai warna bawaan
        boolean noFill;
        double minLon, minLat, maxLon, maxLat;
    }

    static final class Layer {
        String name = "";
        String file = "";          // nama file salinan di penyimpanan aplikasi
        final List<Shape> shapes = new ArrayList<>();
        int skipped;               // elemen yang tidak didukung (mis. GroundOverlay)
        android.graphics.Bitmap image;   // peta gambar (GeoPDF); null untuk peta vektor
        double[] imageToMerc;      // piksel (ix,iy) -> meter Mercator: [a b c d e f], mx = a*ix+b*iy+c, my = d*ix+e*iy+f
        String note = "";          // peringatan kecil untuk pengguna
        boolean isPdf;             // GeoPDF (image bisa null bila disembunyikan & belum dimuat)
        double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;

        boolean isEmpty() { return shapes.isEmpty() && image == null; }

        String summary() {
            if (image != null) return "peta PDF " + image.getWidth() + "×" + image.getHeight() + " px";
            if (isPdf) return "peta PDF";
            int p = 0, l = 0, g = 0;
            for (Shape s : shapes) { if (s.kind == POINT) p++; else if (s.kind == LINE) l++; else g++; }
            StringBuilder b = new StringBuilder();
            if (g > 0) b.append(g).append(" area");
            if (l > 0) b.append(b.length() > 0 ? ", " : "").append(l).append(" garis");
            if (p > 0) b.append(b.length() > 0 ? ", " : "").append(p).append(" titik");
            return b.toString();
        }

        void add(Shape s) {
            s.minLon = s.minLat = Double.MAX_VALUE;
            s.maxLon = s.maxLat = -Double.MAX_VALUE;
            for (double[] part : s.parts) {
                for (int i = 0; i + 1 < part.length; i += 2) {
                    s.minLon = Math.min(s.minLon, part[i]);
                    s.maxLon = Math.max(s.maxLon, part[i]);
                    s.minLat = Math.min(s.minLat, part[i + 1]);
                    s.maxLat = Math.max(s.maxLat, part[i + 1]);
                }
            }
            minLon = Math.min(minLon, s.minLon); maxLon = Math.max(maxLon, s.maxLon);
            minLat = Math.min(minLat, s.minLat); maxLat = Math.max(maxLat, s.maxLat);
            shapes.add(s);
        }
    }

    static final int MAX_BYTES = 30 * 1024 * 1024;

    private GeoFile() { }

    /** Baca isi file. Jenis dikenali dari isinya (bukan dari nama), jadi ekstensi yang salah tetap terbaca. */
    static Layer read(String name, byte[] data) throws IOException {
        Layer layer = new Layer();
        layer.name = name;
        if (data.length >= 2 && data[0] == 'P' && data[1] == 'K') {
            data = kmlFromKmz(data);
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        String t = text.trim();
        try {
            if (t.startsWith("{") || t.startsWith("[")) readGeoJson(t, layer);
            else if (t.startsWith("<")) readKml(t, layer);
            else throw new IOException("Bukan file GeoJSON atau KML");
        } catch (JSONException e) {
            throw new IOException("GeoJSON tidak valid: " + e.getMessage());
        } catch (XmlPullParserException e) {
            throw new IOException("KML tidak valid: " + e.getMessage());
        }
        if (layer.isEmpty()) throw new IOException("Tidak ada titik, garis, atau area yang bisa digambar di file ini");
        return layer;
    }

    private static byte[] kmlFromKmz(byte[] zip) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            byte[] first = null;
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (e.isDirectory() || !n.endsWith(".kml")) continue;
                byte[] b = readLimited(z);
                if (n.equals("doc.kml") || n.endsWith("/doc.kml")) return b;
                if (first == null) first = b;
            }
            if (first != null) return first;
        }
        throw new IOException("File KMZ tidak berisi .kml");
    }

    static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int r; (r = in.read(buf)) > 0; ) {
            bo.write(buf, 0, r);
            if (bo.size() > MAX_BYTES) throw new IOException("File terlalu besar (maks " + (MAX_BYTES >> 20) + " MB)");
        }
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ GeoJSON

    private static void readGeoJson(String text, Layer layer) throws JSONException {
        Object root = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
        if (root instanceof JSONArray) {
            JSONArray a = (JSONArray) root;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) geoObject(o, layer, "", 0, 0, false);
            }
        } else {
            geoObject((JSONObject) root, layer, "", 0, 0, false);
        }
    }

    private static void geoObject(JSONObject o, Layer layer, String name, int stroke, int fill, boolean noFill)
            throws JSONException {
        String type = o.optString("type");
        switch (type) {
            case "FeatureCollection": {
                JSONArray f = o.optJSONArray("features");
                if (f != null) for (int i = 0; i < f.length(); i++) {
                    JSONObject x = f.optJSONObject(i);
                    if (x != null) geoObject(x, layer, "", 0, 0, false);
                }
                return;
            }
            case "Feature": {
                JSONObject props = o.optJSONObject("properties");
                String n = "";
                int s = 0, fl = 0;
                boolean nf = false;
                if (props != null) {
                    n = nameOf(props);
                    s = cssColor(props.optString("stroke"), props.optDouble("stroke-opacity", 1));
                    fl = cssColor(props.optString("fill"), props.optDouble("fill-opacity", 0.25));
                    nf = props.has("fill-opacity") && props.optDouble("fill-opacity", 1) == 0;
                    if (s == 0) s = cssColor(props.optString("marker-color"), 1);
                }
                JSONObject g = o.optJSONObject("geometry");
                if (g != null) geoObject(g, layer, n, s, fl, nf);
                return;
            }
            case "GeometryCollection": {
                JSONArray g = o.optJSONArray("geometries");
                if (g != null) for (int i = 0; i < g.length(); i++) {
                    JSONObject x = g.optJSONObject(i);
                    if (x != null) geoObject(x, layer, name, stroke, fill, noFill);
                }
                return;
            }
            default:
        }
        JSONArray c = o.optJSONArray("coordinates");
        if (c == null) return;
        switch (type) {
            case "Point":
                addShape(layer, POINT, name, stroke, fill, noFill, new double[][]{position(c)});
                break;
            case "MultiPoint":
                for (int i = 0; i < c.length(); i++) addShape(layer, POINT, name, stroke, fill, noFill,
                        new double[][]{position(c.getJSONArray(i))});
                break;
            case "LineString":
                addShape(layer, LINE, name, stroke, fill, noFill, new double[][]{positions(c)});
                break;
            case "MultiLineString":
                for (int i = 0; i < c.length(); i++) addShape(layer, LINE, name, stroke, fill, noFill,
                        new double[][]{positions(c.getJSONArray(i))});
                break;
            case "Polygon":
                addShape(layer, POLY, name, stroke, fill, noFill, rings(c));
                break;
            case "MultiPolygon":
                for (int i = 0; i < c.length(); i++) addShape(layer, POLY, name, stroke, fill, noFill,
                        rings(c.getJSONArray(i)));
                break;
            default:
                layer.skipped++;
        }
    }

    private static String nameOf(JSONObject p) {
        for (String k : new String[]{"name", "Name", "NAME", "nama", "Nama", "NAMA", "title", "label", "blok", "Blok", "BLOK", "id", "ID"}) {
            Object v = p.opt(k);
            if (v != null && v != JSONObject.NULL) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    private static double[] position(JSONArray p) throws JSONException {
        if (p.length() < 2) return new double[0];
        return new double[]{p.getDouble(0), p.getDouble(1)};
    }

    private static double[] positions(JSONArray a) throws JSONException {
        double[] out = new double[a.length() * 2];
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONArray p = a.optJSONArray(i);
            if (p == null || p.length() < 2) continue;
            out[n++] = p.getDouble(0);
            out[n++] = p.getDouble(1);
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    private static double[][] rings(JSONArray a) throws JSONException {
        double[][] out = new double[a.length()][];
        for (int i = 0; i < a.length(); i++) out[i] = positions(a.getJSONArray(i));
        return out;
    }

    /** Warna CSS "#rgb" / "#rrggbb" (simplestyle) -> ARGB, 0 bila kosong/tidak dikenal. */
    static int cssColor(String s, double opacity) {
        if (s == null) return 0;
        s = s.trim();
        if (!s.startsWith("#")) return 0;
        s = s.substring(1);
        if (s.length() == 3) s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        if (s.length() != 6) return 0;
        try {
            int rgb = Integer.parseInt(s, 16);
            int a = (int) Math.round(Math.max(0, Math.min(1, opacity)) * 255);
            return (a << 24) | rgb;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ KML

    /** Pohon XML sederhana (KML biasanya kecil, jadi lebih mudah dibaca sebagai pohon). */
    private static final class Node {
        final String name;     // tanpa awalan namespace, mis. "Track" untuk gx:Track
        String id;
        final StringBuilder text = new StringBuilder();
        final List<Node> kids = new ArrayList<>();
        Node(String name) { this.name = name; }

        Node child(String n) {
            for (Node k : kids) if (k.name.equals(n)) return k;
            return null;
        }

        String childText(String n) {
            Node k = child(n);
            return k == null ? "" : k.text.toString().trim();
        }
    }

    private static Node parseXml(String text) throws XmlPullParserException, IOException {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(text));
        Node root = new Node("#root");
        List<Node> stack = new ArrayList<>();
        stack.add(root);
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                int colon = n.indexOf(':');
                Node node = new Node(colon >= 0 ? n.substring(colon + 1) : n);
                node.id = p.getAttributeValue(null, "id");
                stack.get(stack.size() - 1).kids.add(node);
                stack.add(node);
            } else if (ev == XmlPullParser.END_TAG) {
                if (stack.size() > 1) stack.remove(stack.size() - 1);
            } else if (ev == XmlPullParser.TEXT) {
                stack.get(stack.size() - 1).text.append(p.getText());
            }
        }
        return root;
    }

    private static final class KStyle {
        int line, poly;
        boolean noFill;
        String normalUrl;      // untuk StyleMap
    }

    private static void readKml(String text, Layer layer) throws XmlPullParserException, IOException {
        Node root = parseXml(text);
        Map<String, KStyle> styles = new HashMap<>();
        collectStyles(root, styles);
        walkKml(root, layer, styles);
    }

    private static void collectStyles(Node n, Map<String, KStyle> styles) {
        for (Node k : n.kids) {
            if (k.name.equals("Style") && k.id != null) {
                styles.put(k.id, styleOf(k));
            } else if (k.name.equals("StyleMap") && k.id != null) {
                KStyle s = new KStyle();
                for (Node pair : k.kids) {
                    if (!pair.name.equals("Pair") || !pair.childText("key").equals("normal")) continue;
                    Node inline = pair.child("Style");
                    if (inline != null) s = styleOf(inline);
                    else s.normalUrl = pair.childText("styleUrl");
                }
                styles.put(k.id, s);
            } else {
                collectStyles(k, styles);
            }
        }
    }

    private static KStyle styleOf(Node style) {
        KStyle s = new KStyle();
        Node ls = style.child("LineStyle");
        if (ls != null) s.line = kmlColor(ls.childText("color"));
        Node ps = style.child("PolyStyle");
        if (ps != null) {
            s.poly = kmlColor(ps.childText("color"));
            s.noFill = ps.childText("fill").equals("0");
        }
        if (s.line == 0) {
            Node is = style.child("IconStyle");
            if (is != null) s.line = kmlColor(is.childText("color"));
        }
        return s;
    }

    private static KStyle resolve(String url, Map<String, KStyle> styles, int depth) {
        if (url == null || depth > 4) return null;
        int hash = url.indexOf('#');
        if (hash < 0) return null;  // gaya di file lain tidak didukung
        KStyle s = styles.get(url.substring(hash + 1));
        if (s != null && s.normalUrl != null) return resolve(s.normalUrl, styles, depth + 1);
        return s;
    }

    /** KML memakai aabbggrr. */
    static int kmlColor(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 8) return 0;
        try {
            long v = Long.parseLong(s, 16);
            int a = (int) (v >>> 24) & 0xff, b = (int) (v >>> 16) & 0xff, g = (int) (v >>> 8) & 0xff, r = (int) v & 0xff;
            return (a << 24) | (r << 16) | (g << 8) | b;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void walkKml(Node n, Layer layer, Map<String, KStyle> styles) {
        for (Node k : n.kids) {
            switch (k.name) {
                case "Placemark": placemark(k, layer, styles); break;
                case "GroundOverlay": case "PhotoOverlay": case "ScreenOverlay": case "NetworkLink":
                    layer.skipped++;
                    break;
                case "Style": case "StyleMap": break;
                default: walkKml(k, layer, styles);
            }
        }
    }

    private static void placemark(Node pm, Layer layer, Map<String, KStyle> styles) {
        String name = pm.childText("name");
        KStyle st = null;
        Node inline = pm.child("Style");
        if (inline != null) st = styleOf(inline);
        else st = resolve(pm.childText("styleUrl"), styles, 0);
        int stroke = st != null ? st.line : 0, fill = st != null ? st.poly : 0;
        boolean noFill = st != null && st.noFill;
        for (Node g : pm.kids) geometry(g, layer, name, stroke, fill, noFill);
    }

    private static void geometry(Node g, Layer layer, String name, int stroke, int fill, boolean noFill) {
        switch (g.name) {
            case "Point":
                addShape(layer, POINT, name, stroke, fill, noFill, new double[][]{coords(g.childText("coordinates"))});
                break;
            case "LineString":
                addShape(layer, LINE, name, stroke, fill, noFill, new double[][]{coords(g.childText("coordinates"))});
                break;
            case "LinearRing":
                addShape(layer, POLY, name, stroke, fill, noFill, new double[][]{coords(g.childText("coordinates"))});
                break;
            case "Polygon": {
                List<double[]> rings = new ArrayList<>();
                for (String side : new String[]{"outerBoundaryIs", "innerBoundaryIs"}) {
                    for (Node b : g.kids) {
                        if (!b.name.equals(side)) continue;
                        Node ring = b.child("LinearRing");
                        if (ring != null) rings.add(coords(ring.childText("coordinates")));
                    }
                }
                if (!rings.isEmpty()) addShape(layer, POLY, name, stroke, fill, noFill, rings.toArray(new double[0][]));
                break;
            }
            case "MultiGeometry": case "MultiTrack":
                for (Node k : g.kids) geometry(k, layer, name, stroke, fill, noFill);
                break;
            case "Track": {  // gx:Track: <gx:coord>lon lat alt</gx:coord>
                double[] pts = new double[g.kids.size() * 2];
                int n = 0;
                for (Node k : g.kids) {
                    if (!k.name.equals("coord")) continue;
                    String[] f = k.text.toString().trim().split("\\s+");
                    if (f.length < 2) continue;
                    try {
                        double lon = Double.parseDouble(f[0]), lat = Double.parseDouble(f[1]);
                        pts[n++] = lon; pts[n++] = lat;
                    } catch (NumberFormatException ignored) { }
                }
                addShape(layer, LINE, name, stroke, fill, noFill, new double[][]{java.util.Arrays.copyOf(pts, n)});
                break;
            }
            case "Model":
                layer.skipped++;
                break;
            default:
        }
    }

    /** "lon,lat[,alt] lon,lat[,alt] ..." */
    private static double[] coords(String s) {
        String[] tuples = s.trim().split("\\s+");
        double[] out = new double[tuples.length * 2];
        int n = 0;
        for (String t : tuples) {
            String[] f = t.split(",");
            if (f.length < 2) continue;
            try {
                double lon = Double.parseDouble(f[0]), lat = Double.parseDouble(f[1]);
                out[n++] = lon; out[n++] = lat;
            } catch (NumberFormatException ignored) { }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    // ------------------------------------------------------------------ bersama

    private static void addShape(Layer layer, int kind, String name, int stroke, int fill, boolean noFill, double[][] parts) {
        List<double[]> ok = new ArrayList<>();
        for (double[] p : parts) {
            double[] c = clean(p);
            int min = kind == POINT ? 2 : kind == LINE ? 4 : 6;
            if (c.length >= min) ok.add(c);
            else if (kind == POLY && ok.isEmpty()) return;  // cincin luar rusak: lewati poligon
        }
        if (ok.isEmpty()) { layer.skipped++; return; }
        Shape s = new Shape();
        s.kind = kind;
        s.name = name == null ? "" : name;
        s.parts = ok.toArray(new double[0][]);
        s.stroke = stroke;
        s.fill = fill;
        s.noFill = noFill;
        layer.add(s);
    }

    /** Buang koordinat di luar batas bumi / NaN. */
    private static double[] clean(double[] p) {
        double[] out = new double[p.length];
        int n = 0;
        for (int i = 0; i + 1 < p.length; i += 2) {
            double lon = p[i], lat = p[i + 1];
            if (Double.isNaN(lon) || Double.isNaN(lat) || lon < -180 || lon > 180 || lat < -90 || lat > 90) continue;
            out[n++] = lon;
            out[n++] = Math.max(-85, Math.min(85, lat));
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }
}
