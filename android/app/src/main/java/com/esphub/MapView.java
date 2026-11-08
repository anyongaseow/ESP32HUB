package com.esphub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Peta vektor offline: menggambar lapisan GeoJSON/KML yang diimpor (proyeksi Web Mercator)
 * dan titik lokasi GPS sensor di atasnya. Geser dengan satu jari, cubit untuk zoom,
 * ketuk dua kali untuk memperbesar. Tidak memakai tile/internet sama sekali.
 */
final class MapView extends View {

    static final double EARTH = 6378137.0;

    /** Bentuk yang sudah diproyeksikan ke meter Mercator, relatif ke titik acuan (ox, oy). */
    private static final class Proj {
        GeoFile.Shape src;
        float[][] parts;
        float minX, minY, maxX, maxY;
        float cx, cy;              // titik label
    }

    private final List<GeoFile.Layer> layers = new ArrayList<>();
    private final List<Proj> shapes = new ArrayList<>();
    private double ox, oy;          // acuan proyeksi (meter Mercator)
    private boolean hasOrigin;

    // pandangan: pusat layar (meter relatif acuan) dan skala (piksel per meter Mercator)
    private double cx, cy, scale = 0;

    // GPS sensor (ESP)
    private boolean hasGps, gpsLive;
    private double gpsLon, gpsLat;
    private final List<double[]> trail = new ArrayList<>();
    private static final int TRAIL_MAX = 500;

    // lokasi HP
    private boolean hasPhone, phoneLive;
    private double phoneLon, phoneLat;
    private float phoneAcc;

    /** Titik yang ditambahkan pengguna di peta (belum disimpan sebagai peta). */
    static final class Pin {
        double lon, lat;
        String name = "";
        long time;
        float acc;     // akurasi GPS HP saat diambil (meter), 0 = tidak diketahui
    }

    private final List<Pin> pins = new ArrayList<>();

    interface OnTap { void onTap(double lon, double lat, float x, float y); }

    private OnTap onTap;
    private final int cPhone, cPin;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix imgMatrix = new android.graphics.Matrix();
    private final float[] mv = new float[9];
    private final int cBg, cShape, cFill, cText, cSub, cGps, cGpsOld;
    private final float dp;

    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;

    MapView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        cBg = Ui.color(c, R.color.bg);
        cText = Ui.color(c, R.color.text);
        cSub = Ui.color(c, R.color.sub);
        cGps = Ui.color(c, R.color.accent);
        cShape = cSub;
        cFill = (cSub & 0x00ffffff) | 0x26000000;
        cGpsOld = cSub;
        cPhone = Ui.color(c, R.color.ok);
        cPin = Ui.color(c, R.color.warn);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        label.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        label.setColor(cText);
        label.setTextAlign(Paint.Align.CENTER);
        halo.set(label);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(3 * dp);
        halo.setColor(cBg);

        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAt(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (scale <= 0) return false;
                cx += dx / scale;
                cy -= dy / scale;
                invalidate();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                zoomAt(2f, e.getX(), e.getY());
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (onTap == null || scale <= 0) return false;
                double[] ll = toLonLat(e.getX(), e.getY());
                onTap.onTap(ll[0], ll[1], e.getX(), e.getY());
                return true;
            }
        });
        setContentDescription("Peta");
    }

    // ------------------------------------------------------------------ data

    void setLayers(List<GeoFile.Layer> list) {
        layers.clear();
        layers.addAll(list);
        shapes.clear();
        double[] b = bounds(false);
        if (b != null) setOrigin((b[0] + b[2]) / 2, (b[1] + b[3]) / 2);
        else if (hasGps) setOrigin(gpsLon, gpsLat);
        else if (hasPhone) setOrigin(phoneLon, phoneLat);
        for (GeoFile.Layer l : layers) for (GeoFile.Shape s : l.shapes) shapes.add(project(s));
        invalidate();
    }

    List<GeoFile.Layer> layers() { return layers; }

    /** Ganti titik acuan proyeksi tanpa menggeser pandangan di layar. */
    private void setOrigin(double lon, double lat) {
        double ax = ox + cx, ay = oy + cy;
        boolean had = hasOrigin;
        ox = mx(lon);
        oy = my(lat);
        hasOrigin = true;
        if (had) { cx = ax - ox; cy = ay - oy; } else { cx = cy = 0; }
    }

    void setOnTap(OnTap t) { onTap = t; }

    void setPins(List<Pin> list) {
        pins.clear();
        pins.addAll(list);
        invalidate();
    }

    /** Indeks titik buatan pengguna di dekat (x, y) layar, -1 bila tidak ada. */
    int pinAt(float x, float y) {
        float r = 24 * dp;
        int best = -1;
        float bestD = r * r;
        for (int i = 0; i < pins.size(); i++) {
            Pin p = pins.get(i);
            float dx = sx(mx(p.lon) - ox) - x, dy = sy(my(p.lat) - oy) - y;
            float d = dx * dx + dy * dy;
            if (d <= bestD) { bestD = d; best = i; }
        }
        return best;
    }

    double[] toLonLat(float x, float y) {
        double mxa = ox + cx + (x - getWidth() / 2.0) / scale;
        double mya = oy + cy - (y - getHeight() / 2.0) / scale;
        return new double[]{Math.toDegrees(mxa / EARTH), Math.toDegrees(2 * Math.atan(Math.exp(mya / EARTH)) - Math.PI / 2)};
    }

    /** Lokasi HP. live = dari GPS HP barusan; acc = akurasi (meter), 0 bila tidak diketahui. */
    void setPhone(double lon, double lat, float acc, boolean live) {
        boolean first = !hasPhone;
        hasPhone = true;
        phoneLive = live;
        phoneLon = lon;
        phoneLat = lat;
        phoneAcc = acc;
        if (!hasOrigin) setOrigin(lon, lat);
        if (first && layers.isEmpty() && !hasGps && getWidth() > 0) centerOn(lon, lat);
        invalidate();
    }

    void setPhoneLive(boolean live) {
        if (phoneLive != live) { phoneLive = live; invalidate(); }
    }

    boolean hasPhone() { return hasPhone; }
    double[] phone() { return hasPhone ? new double[]{phoneLon, phoneLat, phoneAcc} : null; }
    double[] gps() { return hasGps ? new double[]{gpsLon, gpsLat} : null; }
    boolean gpsLive() { return gpsLive; }

    void centerOn(double lon, double lat) {
        if (getWidth() == 0) return;
        if (!hasOrigin) setOrigin(lon, lat);
        cx = mx(lon) - ox;
        cy = my(lat) - oy;
        if (scale < 0.2) scale = 1;
        clampScale();
        invalidate();
    }

    private Proj project(GeoFile.Shape s) {
        Proj p = new Proj();
        p.src = s;
        p.parts = new float[s.parts.length][];
        p.minX = p.minY = Float.MAX_VALUE;
        p.maxX = p.maxY = -Float.MAX_VALUE;
        for (int i = 0; i < s.parts.length; i++) {
            double[] src = s.parts[i];
            float[] dst = new float[src.length];
            for (int j = 0; j + 1 < src.length; j += 2) {
                float x = (float) (mx(src[j]) - ox), y = (float) (my(src[j + 1]) - oy);
                dst[j] = x; dst[j + 1] = y;
                p.minX = Math.min(p.minX, x); p.maxX = Math.max(p.maxX, x);
                p.minY = Math.min(p.minY, y); p.maxY = Math.max(p.maxY, y);
            }
            p.parts[i] = dst;
        }
        p.cx = (p.minX + p.maxX) / 2;
        p.cy = (p.minY + p.maxY) / 2;
        return p;
    }

    /** Posisi GPS terbaru. live = data baru dari ESP; false = posisi terakhir yang tersimpan. */
    void setGps(double lon, double lat, boolean live) {
        boolean first = !hasGps;
        hasGps = true;
        gpsLive = live;
        gpsLon = lon;
        gpsLat = lat;
        if (live) {
            double[] last = trail.isEmpty() ? null : trail.get(trail.size() - 1);
            if (last == null || last[0] != lon || last[1] != lat) {
                trail.add(new double[]{lon, lat});
                if (trail.size() > TRAIL_MAX) trail.remove(0);
            }
        }
        if (!hasOrigin) setOrigin(lon, lat);
        if (first && layers.isEmpty() && getWidth() > 0) centerOnGps();
        invalidate();
    }

    void setGpsLive(boolean live) {
        if (gpsLive != live) { gpsLive = live; invalidate(); }
    }

    boolean hasGps() { return hasGps; }

    // ------------------------------------------------------------------ pandangan

    /** Pas-kan seluruh peta (dan titik GPS) ke layar. */
    void fitAll() {
        double[] b = bounds(true);
        if (b == null || getWidth() == 0) return;
        double x0 = mx(b[0]) - ox, x1 = mx(b[2]) - ox, y0 = my(b[1]) - oy, y1 = my(b[3]) - oy;
        cx = (x0 + x1) / 2;
        cy = (y0 + y1) / 2;
        double w = Math.max(x1 - x0, 1), h = Math.max(y1 - y0, 1);
        float pad = 32 * dp;
        scale = Math.min((getWidth() - 2 * pad) / w, (getHeight() - 2 * pad) / h);
        if (x1 - x0 < 1 && y1 - y0 < 1) scale = 1;  // satu titik saja: ±400 m di layar
        clampScale();
        invalidate();
    }

    void centerOnGps() {
        if (hasGps) centerOn(gpsLon, gpsLat);
    }

    private void zoomAt(float f, float fx, float fy) {
        if (scale <= 0) return;
        double wx = cx + (fx - getWidth() / 2.0) / scale;
        double wy = cy - (fy - getHeight() / 2.0) / scale;
        scale *= f;
        clampScale();
        cx = wx - (fx - getWidth() / 2.0) / scale;
        cy = wy + (fy - getHeight() / 2.0) / scale;
        invalidate();
    }

    private void clampScale() {
        // dari ~seluruh dunia sampai ~2 cm per piksel
        scale = Math.max(getWidth() / (2 * Math.PI * EARTH), Math.min(50, scale));
    }

    /** [minLon, minLat, maxLon, maxLat] gabungan semua lapisan (+GPS), null bila kosong. */
    private double[] bounds(boolean withGps) {
        double a = Double.MAX_VALUE, b = Double.MAX_VALUE, c = -Double.MAX_VALUE, d = -Double.MAX_VALUE;
        for (GeoFile.Layer l : layers) {
            if (l.isEmpty()) continue;
            a = Math.min(a, l.minLon); b = Math.min(b, l.minLat);
            c = Math.max(c, l.maxLon); d = Math.max(d, l.maxLat);
        }
        if (withGps) {
            List<double[]> extra = new ArrayList<>();
            if (hasGps) extra.add(new double[]{gpsLon, gpsLat});
            if (hasPhone) extra.add(new double[]{phoneLon, phoneLat});
            for (Pin p : pins) extra.add(new double[]{p.lon, p.lat});
            for (double[] e : extra) {
                a = Math.min(a, e[0]); b = Math.min(b, e[1]);
                c = Math.max(c, e[0]); d = Math.max(d, e[1]);
            }
        }
        return a == Double.MAX_VALUE ? null : new double[]{a, b, c, d};
    }

    static double mx(double lon) { return Math.toRadians(lon) * EARTH; }
    static double my(double lat) {
        double r = Math.toRadians(Math.max(-85, Math.min(85, lat)));
        return EARTH * Math.log(Math.tan(Math.PI / 4 + r / 2));
    }

    private float sx(double x) { return (float) (getWidth() / 2.0 + (x - cx) * scale); }
    private float sy(double y) { return (float) (getHeight() / 2.0 - (y - cy) * scale); }

    // ------------------------------------------------------------------ gambar

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (scale <= 0 && w > 0) {
            if (!layers.isEmpty() || !pins.isEmpty()) fitAll();
            else if (hasGps) centerOnGps();
            else if (hasPhone) centerOn(phoneLon, phoneLat);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        if (!pinch.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(cBg);
        int w = getWidth(), h = getHeight();
        if (scale <= 0) {
            empty(c, layers.isEmpty() && !hasGps && !hasPhone
                    ? "Belum ada peta.\nKetuk Impor untuk memilih GeoJSON, KML, atau GeoPDF."
                    : "");
            return;
        }
        // batas layar dalam meter (relatif acuan) untuk melewati bentuk di luar layar
        float vx0 = (float) (cx - w / 2.0 / scale), vx1 = (float) (cx + w / 2.0 / scale);
        float vy0 = (float) (cy - h / 2.0 / scale), vy1 = (float) (cy + h / 2.0 / scale);
        float lineW = 2 * dp;

        // peta gambar (GeoPDF) paling bawah, di bawah area/garis/titik dan GPS
        for (GeoFile.Layer l : layers) {
            if (l.image == null || l.image.isRecycled()) continue;
            double[] a = l.imageToMerc;
            // layar = W/2 + (mx - ox - cx)*scale ; H/2 - (my - oy - cy)*scale
            mv[0] = (float) (scale * a[0]);
            mv[1] = (float) (scale * a[1]);
            mv[2] = (float) (w / 2.0 + (a[2] - ox - cx) * scale);
            mv[3] = (float) (-scale * a[3]);
            mv[4] = (float) (-scale * a[4]);
            mv[5] = (float) (h / 2.0 - (a[5] - oy - cy) * scale);
            mv[6] = 0; mv[7] = 0; mv[8] = 1;
            imgMatrix.setValues(mv);
            c.drawBitmap(l.image, imgMatrix, bitmapPaint);
        }

        // area dulu, lalu garis, lalu titik (titik selalu di atas)
        for (int pass = 0; pass < 3; pass++) {
            int kind = pass == 0 ? GeoFile.POLY : pass == 1 ? GeoFile.LINE : GeoFile.POINT;
            for (Proj p : shapes) {
                if (p.src.kind != kind) continue;
                if (p.maxX < vx0 || p.minX > vx1 || p.maxY < vy0 || p.minY > vy1) continue;
                int sc = p.src.stroke != 0 ? p.src.stroke : cShape;
                if (kind == GeoFile.POINT) {
                    float x = sx(p.parts[0][0]), y = sy(p.parts[0][1]);
                    fill.setColor(sc | 0xff000000);
                    c.drawCircle(x, y, 4.5f * dp, fill);
                    stroke.setColor(cBg);
                    stroke.setStrokeWidth(1.5f * dp);
                    c.drawCircle(x, y, 4.5f * dp, stroke);
                    continue;
                }
                path.rewind();
                for (float[] part : p.parts) {
                    path.moveTo(sx(part[0]), sy(part[1]));
                    for (int j = 2; j + 1 < part.length; j += 2) path.lineTo(sx(part[j]), sy(part[j + 1]));
                    if (kind == GeoFile.POLY) path.close();
                }
                if (kind == GeoFile.POLY && !p.src.noFill) {
                    path.setFillType(Path.FillType.EVEN_ODD);
                    fill.setColor(p.src.fill != 0 ? p.src.fill : (p.src.stroke != 0 ? (p.src.stroke & 0x00ffffff) | 0x33000000 : cFill));
                    c.drawPath(path, fill);
                }
                stroke.setColor(sc);
                stroke.setStrokeWidth(kind == GeoFile.POLY ? 1.5f * dp : lineW);
                c.drawPath(path, stroke);
            }
        }
        drawLabels(c, vx0, vx1, vy0, vy1);
        drawPins(c);
        drawPhone(c);
        drawGps(c);
        drawScaleBar(c);
    }

    /** Nama titik dan area, hanya bila cukup ruang dan tidak terlalu banyak. */
    private void drawLabels(Canvas c, float vx0, float vx1, float vy0, float vy1) {
        int drawn = 0;
        float minArea = 70 * dp;
        List<float[]> used = new ArrayList<>();
        for (Proj p : shapes) {
            if (drawn >= 80) break;
            String n = p.src.name;
            if (n.isEmpty()) continue;
            if (p.cx < vx0 || p.cx > vx1 || p.cy < vy0 || p.cy > vy1) continue;
            float x = sx(p.cx), y = sy(p.cy);
            if (p.src.kind == GeoFile.POINT) {
                y -= 9 * dp;
            } else {
                float sw = (float) ((p.maxX - p.minX) * scale), sh = (float) ((p.maxY - p.minY) * scale);
                if (Math.max(sw, sh) < minArea) continue;
                if (p.src.kind == GeoFile.LINE) continue;  // label garis tidak digambar
                y += label.getTextSize() / 3;
            }
            if (n.length() > 28) n = n.substring(0, 27) + "…";
            float tw = label.measureText(n) / 2 + 4 * dp, th = label.getTextSize() / 2 + 2 * dp;
            boolean overlap = false;
            for (float[] r : used) {
                if (Math.abs(r[0] - x) < r[2] + tw && Math.abs(r[1] - y) < r[3] + th) { overlap = true; break; }
            }
            if (overlap) continue;
            used.add(new float[]{x, y, tw, th});
            c.drawText(n, x, y, halo);
            c.drawText(n, x, y, label);
            drawn++;
        }
    }

    /** Titik buatan pengguna: bulat oranye + nama, selalu di atas peta. */
    private void drawPins(Canvas c) {
        for (Pin p : pins) {
            float x = sx(mx(p.lon) - ox), y = sy(my(p.lat) - oy);
            if (x < -50 * dp || y < -50 * dp || x > getWidth() + 50 * dp || y > getHeight() + 50 * dp) continue;
            fill.setColor(Color.WHITE);
            c.drawCircle(x, y, 8 * dp, fill);
            fill.setColor(cPin);
            c.drawCircle(x, y, 6 * dp, fill);
            if (!p.name.isEmpty()) {
                float ty = y - 12 * dp;
                c.drawText(p.name, x, ty, halo);
                c.drawText(p.name, x, ty, label);
            }
        }
    }

    /** Lokasi HP: titik hijau + lingkaran akurasi. */
    private void drawPhone(Canvas c) {
        if (!hasPhone) return;
        int col = phoneLive ? cPhone : cGpsOld;
        float x = sx(mx(phoneLon) - ox), y = sy(my(phoneLat) - oy);
        if (phoneAcc > 0) {
            // meter di tanah -> meter Mercator = / cos(lintang)
            float r = (float) (phoneAcc / Math.cos(Math.toRadians(phoneLat)) * scale);
            if (r > 10 * dp && r < 4 * Math.max(getWidth(), getHeight())) {
                fill.setColor((col & 0x00ffffff) | 0x22000000);
                c.drawCircle(x, y, r, fill);
                stroke.setColor((col & 0x00ffffff) | 0x66000000);
                stroke.setStrokeWidth(1 * dp);
                c.drawCircle(x, y, r, stroke);
            }
        }
        fill.setColor(Color.WHITE);
        c.drawCircle(x, y, 8.5f * dp, fill);
        fill.setColor(col);
        c.drawCircle(x, y, 6 * dp, fill);
        pill(c, phoneLive ? "HP" : "HP (terakhir)", x, y - 20 * dp, col);
    }

    /** Label bulat berwarna di atas titik (y = sisi bawah pil). */
    private void pill(Canvas c, String t, float x, float bottom, int col) {
        Typeface old = label.getTypeface();
        label.setTypeface(Typeface.DEFAULT_BOLD);
        float tw = label.measureText(t), th = label.getTextSize();
        float px = 8 * dp, top = bottom - th - 8 * dp;
        fill.setColor(col);
        c.drawRoundRect(x - tw / 2 - px, top, x + tw / 2 + px, bottom, 12 * dp, 12 * dp, fill);
        label.setColor(Color.WHITE);
        c.drawText(t, x, top + 4 * dp + th * 0.8f, label);
        label.setColor(cText);
        label.setTypeface(old);
    }

    private void drawGps(Canvas c) {
        if (!hasGps) return;
        int col = gpsLive ? cGps : cGpsOld;
        if (trail.size() > 1) {
            path.rewind();
            for (int i = 0; i < trail.size(); i++) {
                double[] t = trail.get(i);
                float x = sx(mx(t[0]) - ox), y = sy(my(t[1]) - oy);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            stroke.setColor((col & 0x00ffffff) | 0x99000000);
            stroke.setStrokeWidth(2.5f * dp);
            c.drawPath(path, stroke);
        }
        float x = sx(mx(gpsLon) - ox), y = sy(my(gpsLat) - oy);
        fill.setColor((col & 0x00ffffff) | 0x33000000);
        c.drawCircle(x, y, 18 * dp, fill);
        fill.setColor(Color.WHITE);
        c.drawCircle(x, y, 9 * dp, fill);
        fill.setColor(col);
        c.drawCircle(x, y, 6.5f * dp, fill);
        // label di "pil" berwarna supaya tetap terbaca walau menimpa nama blok
        pill(c, gpsLive ? "Sensor" : "Sensor (terakhir)", x, y - 18 * dp, col);
    }

    private void drawScaleBar(Canvas c) {
        // meter di tanah per piksel = (1/scale) * cos(lintang di pusat layar)
        double lat = Math.toDegrees(2 * Math.atan(Math.exp((cy + oy) / EARTH)) - Math.PI / 2);
        double mpp = Math.cos(Math.toRadians(lat)) / scale;
        double target = 96 * dp * mpp;
        double pow = Math.pow(10, Math.floor(Math.log10(target)));
        double nice = target / pow >= 5 ? 5 * pow : target / pow >= 2 ? 2 * pow : pow;
        float len = (float) (nice / mpp);
        float x0 = 16 * dp, y0 = getHeight() - 16 * dp;
        stroke.setColor(cText);
        stroke.setStrokeWidth(2 * dp);
        c.drawLine(x0, y0, x0 + len, y0, stroke);
        c.drawLine(x0, y0 - 5 * dp, x0, y0, stroke);
        c.drawLine(x0 + len, y0 - 5 * dp, x0 + len, y0, stroke);
        String t = nice >= 1000 ? fmt(nice / 1000) + " km" : fmt(nice) + " m";
        label.setTextAlign(Paint.Align.LEFT);
        halo.setTextAlign(Paint.Align.LEFT);
        c.drawText(t, x0, y0 - 8 * dp, halo);
        c.drawText(t, x0, y0 - 8 * dp, label);
        label.setTextAlign(Paint.Align.CENTER);
        halo.setTextAlign(Paint.Align.CENTER);
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }

    private void empty(Canvas c, String msg) {
        if (msg.isEmpty()) return;
        Paint p = new Paint(label);
        p.setColor(cSub);
        String[] lines = msg.split("\n");
        float lh = p.getTextSize() * 1.5f;
        float y = getHeight() / 2f - (lines.length - 1) * lh / 2;
        for (String l : lines) { c.drawText(l, getWidth() / 2f, y, p); y += lh; }
    }
}
