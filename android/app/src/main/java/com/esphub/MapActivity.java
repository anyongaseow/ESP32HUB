package com.esphub;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Layar Peta: peta yang diimpor (GeoJSON / KML / KMZ / GeoPDF, disimpan di HP, tanpa internet),
 * lokasi sensor GPS dari ESP yang tersambung (GET /gps), lokasi HP, dan titik yang ditambahkan
 * pengguna, yang bisa disimpan sebagai peta baru.
 */
public class MapActivity extends Activity {

    private static final int PICK = 7, REQ_LOC = 8;
    private static final long POLL_MS = 2000, POLL_SLOW_MS = 10000, PHONE_STALE_MS = 15000;

    private Downloader dl;
    private MapView map;
    private TextView sub, sensorStatus, phoneStatus, editHint, addPinBtn;

    /** Titik hanya boleh ditambah bila akurasi GPS HP sebaik ini (meter) atau lebih baik. */
    static final float MAX_ACCURACY_M = 5f;
    private LinearLayout normalBar, editBar;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean resumed, polling, editMode, busy;

    /** Semua lapisan (tampil + disembunyikan), urutan bawah -> atas. */
    private final List<GeoFile.Layer> layers = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private final List<MapView.Pin> pins = new ArrayList<>();

    // lokasi HP
    private LocationManager lm;
    private boolean locAsked, listening;
    private Location phoneFix;
    private long phoneFixAt;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration c = MainActivity.themeOverride(base);
        if (c != null) applyOverrideConfiguration(c);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dl = Downloader.get(this);
        lm = getSystemService(LocationManager.class);

        LinearLayout root = Ui.col(this);
        root.setFitsSystemWindows(true);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        header.addView(Ui.iconButton(this, R.drawable.ic_back, "Kembali", v -> onBackPressed()));
        LinearLayout names = Ui.col(this);
        names.addView(Ui.text(this, "Peta", 18, R.color.text, true));
        sub = Ui.oneLine(Ui.text(this, "", 12, R.color.sub, false), TextUtils.TruncateAt.END);
        names.addView(sub);
        header.addView(names, Ui.weight(1));
        TextView imp = Ui.button(this, "Impor", true);
        imp.setOnClickListener(v -> pickFile());
        header.addView(imp);
        root.addView(header);

        map = new MapView(this);
        map.setOnTap(this::onMapTap);
        root.addView(map, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        root.addView(Ui.divider(this));
        LinearLayout bottom = Ui.col(this);
        bottom.setBackgroundColor(Ui.color(this, R.color.card));
        bottom.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 12));
        sensorStatus = Ui.text(this, "", 13, R.color.sub, false);
        phoneStatus = Ui.text(this, "", 13, R.color.sub, false);
        bottom.addView(sensorStatus);
        bottom.addView(phoneStatus);

        normalBar = Ui.row(this);
        addBtn(normalBar, "Lapisan", false, v -> showLayers());
        addBtn(normalBar, "Semua", false, v -> map.fitAll());
        addBtn(normalBar, "Lokasi", false, v -> goToLocation());
        addBtn(normalBar, "+ Titik", true, v -> setEditMode(true));
        bottom.addView(normalBar, Ui.margins(this, 0, 10, 0, 0));

        LinearLayout edit = Ui.col(this);
        editHint = Ui.text(this, "", 13, R.color.text, false);
        edit.addView(editHint);
        LinearLayout eb = Ui.row(this);
        addPinBtn = addBtn(eb, "Tambah titik", true, v -> addPinHere());
        addBtn(eb, "Simpan peta", false, v -> askSave());
        addBtn(eb, "Selesai", false, v -> setEditMode(false));
        edit.addView(eb, Ui.margins(this, 0, 8, 0, 0));
        editBar = edit;
        bottom.addView(editBar, Ui.margins(this, 0, 10, 0, 0));
        root.addView(bottom, Ui.lp(MATCH_PARENT, WRAP_CONTENT));

        setContentView(root);
        MainActivity.applyBars(this);

        Set<String> h = prefs().getStringSet("hidden", null);
        if (h != null) hidden.addAll(h);
        loadPins();
        map.setPins(pins);
        setEditMode(b != null && b.getBoolean("edit"));
        restoreLastFix();
        loadLayers();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("edit", editMode);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        dl.setHold(true);  // jangan putus otomatis selama peta dibuka
        if (!polling) { polling = true; main.post(poll); }
        startPhone();
    }

    @Override
    protected void onPause() {
        resumed = false;
        dl.setHold(false);
        stopPhone();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (editMode) setEditMode(false);
        else super.onBackPressed();
    }

    private TextView addBtn(LinearLayout row, String label, boolean primary, View.OnClickListener l) {
        if (row.getChildCount() > 0) row.addView(Ui.gap(this, 8));
        TextView b = Ui.button(this, label, primary);
        b.setOnClickListener(l);
        b.setPadding(Ui.dp(this, 6), b.getPaddingTop(), Ui.dp(this, 6), b.getPaddingBottom());
        b.setSingleLine(true);
        row.addView(b, Ui.weight(1));
        return b;
    }

    // ------------------------------------------------------------------ lapisan peta

    private File mapsDir() {
        File d = new File(getFilesDir(), "maps");
        if (!d.isDirectory() && !d.mkdirs()) toast("Folder peta tidak bisa dibuat");
        return d;
    }

    /** Baca semua peta yang pernah diimpor. PDF yang disembunyikan tidak digambar (hemat memori). */
    private void loadLayers() {
        sub.setText("Memuat peta…");
        io.execute(() -> {
            List<GeoFile.Layer> out = new ArrayList<>();
            List<String> bad = new ArrayList<>();
            File[] files = mapsDir().listFiles();
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    try {
                        GeoFile.Layer l = hidden.contains(f.getName()) && isPdfFile(f) ? stub(f) : readFile(f);
                        out.add(l);
                    } catch (IOException | RuntimeException e) {
                        bad.add(displayName(f));
                    }
                }
            }
            main.post(() -> {
                if (isFinishing()) return;
                layers.clear();
                layers.addAll(out);
                applyLayers();
                map.fitAll();
                if (!bad.isEmpty()) toast("Tidak bisa dibaca: " + TextUtils.join(", ", bad));
            });
        });
    }

    private GeoFile.Layer readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            GeoFile.Layer l = readLayer(f, displayName(f), GeoFile.readLimited(in));
            l.file = f.getName();
            return l;
        }
    }

    private static boolean isPdfFile(File f) {
        byte[] head = new byte[1024];
        try (InputStream in = new FileInputStream(f)) {
            int n = in.read(head);
            return n > 4 && GeoPdf.isPdf(Arrays.copyOf(head, n));
        } catch (IOException e) {
            return false;
        }
    }

    /** Lapisan PDF tersembunyi: hanya nama, gambar dimuat saat ditampilkan lagi. */
    private static GeoFile.Layer stub(File f) {
        GeoFile.Layer l = new GeoFile.Layer();
        l.name = displayName(f);
        l.file = f.getName();
        l.isPdf = true;
        return l;
    }

    /** GeoPDF dikenali dari isinya, selain itu GeoJSON/KML/KMZ. */
    private static GeoFile.Layer readLayer(File f, String name, byte[] bytes) throws IOException {
        return GeoPdf.isPdf(bytes) ? GeoPdf.read(name, f, bytes) : GeoFile.read(name, bytes);
    }

    /** Nama file tersimpan = "<waktu>_<nama asli>". */
    private static String displayName(File f) {
        String n = f.getName();
        int u = n.indexOf('_');
        return u > 0 ? n.substring(u + 1) : n;
    }

    private List<GeoFile.Layer> visibleLayers() {
        List<GeoFile.Layer> v = new ArrayList<>();
        for (GeoFile.Layer l : layers) if (!hidden.contains(l.file) && !l.isEmpty()) v.add(l);
        return v;
    }

    private void applyLayers() {
        map.setLayers(visibleLayers());
        updateSub();
    }

    private void saveHidden() {
        prefs().edit().putStringSet("hidden", new HashSet<>(hidden)).apply();
    }

    private void updateSub() {
        List<GeoFile.Layer> v = visibleLayers();
        if (layers.isEmpty()) { sub.setText("Belum ada peta diimpor"); return; }
        if (layers.size() == 1 && v.size() == 1) { sub.setText(v.get(0).name + " · " + v.get(0).summary()); return; }
        sub.setText(v.size() == layers.size() ? layers.size() + " lapisan" : v.size() + " dari " + layers.size() + " lapisan tampil");
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");  // HP sering tidak mengenal tipe MIME KML/GeoJSON, jadi semua file ditampilkan
        try {
            startActivityForResult(i, PICK);
        } catch (android.content.ActivityNotFoundException e) {
            toast("Tidak ada aplikasi pemilih file di HP ini");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = queryName(uri);
        sub.setText("Membaca " + name + "…");
        io.execute(() -> {
            String err = null;
            GeoFile.Layer layer = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("File tidak bisa dibuka");
                layer = storeAndRead(name, GeoFile.readLimited(in));
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            GeoFile.Layer l = layer;
            String fe = err;
            main.post(() -> {
                if (isFinishing()) return;
                if (l == null) {
                    updateSub();
                    new AlertDialog.Builder(this)
                            .setTitle("Gagal mengimpor " + name)
                            .setMessage(fe + "\n\nYang didukung: GeoJSON (.geojson/.json), KML, KMZ, dan GeoPDF (PDF berkoordinat).")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                layers.add(l);
                applyLayers();
                map.fitAll();
                toast("Diimpor: " + l.summary() + (l.skipped > 0 ? " (" + l.skipped + " elemen dilewati)" : "")
                        + (l.note.isEmpty() ? "" : "\nCatatan: " + l.note));
            });
        });
    }

    /** Simpan salinan di aplikasi lalu baca (PDF hanya bisa digambar dari file). Dipanggil di thread io. */
    private GeoFile.Layer storeAndRead(String name, byte[] bytes) throws IOException {
        File out = new File(mapsDir(), System.currentTimeMillis() + "_" + Downloader.safeName(name));
        try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(bytes); }
        try {
            GeoFile.Layer l = readLayer(out, name, bytes);
            l.file = out.getName();
            return l;
        } catch (IOException | RuntimeException e) {
            if (!out.delete()) out.deleteOnExit();
            throw e;
        }
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (RuntimeException ignored) {
        }
        String p = uri.getLastPathSegment();
        return p != null ? p : "peta";
    }

    /** Daftar lapisan: centang = tampil. Hapus lewat tombol terpisah. */
    private void showLayers() {
        if (layers.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Lapisan peta")
                    .setMessage("Belum ada peta. Ketuk Impor lalu pilih file .geojson, .json, .kml, .kmz, atau GeoPDF (.pdf).\n"
                            + "Peta disimpan di HP, jadi tetap tampil tanpa internet.")
                    .setPositiveButton("Impor", (d, w) -> pickFile())
                    .setNegativeButton("Tutup", null)
                    .show();
            return;
        }
        String[] items = new String[layers.size()];
        boolean[] checked = new boolean[layers.size()];
        for (int i = 0; i < layers.size(); i++) {
            GeoFile.Layer l = layers.get(i);
            items[i] = l.name + "  ·  " + l.summary();
            checked[i] = !hidden.contains(l.file);
        }
        new AlertDialog.Builder(this)
                .setTitle("Lapisan (centang = tampil)")
                .setMultiChoiceItems(items, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Terapkan", (d, w) -> applyVisibility(checked))
                .setNeutralButton("Hapus…", (d, w) -> chooseRemove())
                .setNegativeButton("Batal", null)
                .show();
    }

    private void applyVisibility(boolean[] checked) {
        List<GeoFile.Layer> toLoad = new ArrayList<>();
        for (int i = 0; i < checked.length && i < layers.size(); i++) {
            GeoFile.Layer l = layers.get(i);
            if (checked[i]) {
                hidden.remove(l.file);
                if (l.isPdf && l.image == null) toLoad.add(l);
            } else {
                hidden.add(l.file);
            }
        }
        saveHidden();
        applyLayers();
        releaseHiddenImages();
        if (toLoad.isEmpty()) return;
        sub.setText("Memuat peta PDF…");
        io.execute(() -> {
            for (GeoFile.Layer stub : toLoad) {
                try {
                    GeoFile.Layer full = readFile(new File(mapsDir(), stub.file));
                    main.post(() -> {
                        int at = layers.indexOf(stub);
                        if (at >= 0) layers.set(at, full);
                        applyLayers();
                    });
                } catch (IOException | RuntimeException e) {
                    main.post(() -> toast("Gagal memuat " + stub.name + ": " + e.getMessage()));
                }
            }
        });
    }

    /** Gambar PDF yang disembunyikan dilepas dari memori (dimuat lagi saat ditampilkan). */
    private void releaseHiddenImages() {
        for (int i = 0; i < layers.size(); i++) {
            GeoFile.Layer l = layers.get(i);
            if (l.image != null && hidden.contains(l.file)) {
                Bitmap b = l.image;
                GeoFile.Layer s = stub(new File(mapsDir(), l.file));
                s.name = l.name;
                layers.set(i, s);
                b.recycle();
            }
        }
    }

    private void chooseRemove() {
        String[] items = new String[layers.size()];
        for (int i = 0; i < layers.size(); i++) items[i] = layers.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Hapus lapisan mana?")
                .setItems(items, (d, which) -> confirmRemove(layers.get(which)))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void confirmRemove(GeoFile.Layer l) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus " + l.name + "?")
                .setMessage("Peta ini dihapus dari aplikasi. File asli di HP (dan salinan di Download) tidak ikut terhapus.")
                .setPositiveButton("Hapus", (d, w) -> {
                    File f = new File(mapsDir(), l.file);
                    if (f.exists() && !f.delete()) { toast("Gagal menghapus"); return; }
                    layers.remove(l);
                    hidden.remove(l.file);
                    saveHidden();
                    applyLayers();
                    if (l.image != null) l.image.recycle();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ------------------------------------------------------------------ titik buatan pengguna

    private void setEditMode(boolean on) {
        editMode = on;
        normalBar.setVisibility(on ? View.GONE : View.VISIBLE);
        editBar.setVisibility(on ? View.VISIBLE : View.GONE);
        updateEditHint();
    }

    /** Petunjuk mode titik + akurasi GPS HP saat ini; tombol Tambah titik redup bila akurasi belum cukup. */
    private void updateEditHint() {
        if (editHint == null) return;
        String why = accuracyProblem();
        StringBuilder t = new StringBuilder();
        if (why == null) {
            t.append(String.format(Locale.US, "GPS HP siap (±%.1f m). Tekan Tambah titik di posisi Anda.", phoneFix.getAccuracy()));
        } else {
            t.append(why);
        }
        if (!pins.isEmpty()) t.append("\n").append(pins.size()).append(" titik baru · ketuk titik oranye untuk ganti nama/hapus.");
        editHint.setText(t);
        editHint.setTextColor(Ui.color(this, why == null ? R.color.ok : R.color.warn));
        if (addPinBtn != null) addPinBtn.setAlpha(why == null ? 1f : 0.45f);
    }

    /** null bila GPS HP siap untuk menambah titik, kalau tidak alasannya (dipakai juga sebagai peringatan). */
    private String accuracyProblem() {
        if (!hasLocationPermission()) return "Izin lokasi HP belum diberikan, titik belum bisa ditambah.";
        if (lm != null && !lm.isLocationEnabled()) return "Lokasi/GPS HP mati. Nyalakan dulu untuk menambah titik.";
        if (phoneFix == null) return "Menunggu sinyal GPS HP…";
        if (System.currentTimeMillis() - phoneFixAt >= PHONE_STALE_MS) return "Sinyal GPS HP hilang. Tunggu sampai lokasi HP diperbarui.";
        if (!phoneFix.hasAccuracy()) return "Akurasi GPS HP tidak diketahui. Tunggu sinyal yang lebih baik.";
        float acc = phoneFix.getAccuracy();
        if (acc > MAX_ACCURACY_M) {
            return String.format(Locale.US, "Akurasi GPS ±%.1f m (harus ≤ %.0f m). Tunggu di tempat terbuka, jauhkan dari pohon/atap.",
                    acc, MAX_ACCURACY_M);
        }
        return null;
    }

    private void onMapTap(double lon, double lat, float x, float y) {
        // titik hanya ditambah lewat tombol (posisi GPS HP), bukan dengan mengetuk peta
        int idx = map.pinAt(x, y);
        if (idx < 0) return;
        if (editMode) editPin(idx);
        else {
            MapView.Pin p = pins.get(idx);
            toast((p.name.isEmpty() ? "Titik" : p.name) + "\n" + coord(p.lat, p.lon)
                    + (p.acc > 0 ? String.format(Locale.US, " · ±%.1f m", p.acc) : ""));
        }
    }

    /** Satu tekan: titik di posisi GPS HP dengan nama otomatis, hanya bila akurasi ≤ 5 m. */
    private void addPinHere() {
        String why = accuracyProblem();
        if (why != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Titik tidak ditambahkan")
                    .setMessage(why)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        Location fix = phoneFix;
        int seq = prefs().getInt("pinSeq", 0) + 1;
        prefs().edit().putInt("pinSeq", seq).apply();
        MapView.Pin p = new MapView.Pin();
        p.lon = fix.getLongitude();
        p.lat = fix.getLatitude();
        p.acc = fix.getAccuracy();
        p.name = "Titik " + seq;
        p.time = System.currentTimeMillis();
        pins.add(p);
        pinsChanged();
        toast(String.format(Locale.US, "%s ditambahkan (±%.1f m)", p.name, p.acc));
    }

    private void editPin(int idx) {
        MapView.Pin p = pins.get(idx);
        EditText in = Ui.textInput(this, "Nama titik", p.name);
        LinearLayout body = Ui.dialogBody(this);
        body.addView(Ui.label(this, coord(p.lat, p.lon) + " · dibuat " + new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date(p.time))));
        body.addView(in);
        new AlertDialog.Builder(this)
                .setTitle("Ubah titik")
                .setView(body)
                .setPositiveButton("Simpan", (d, w) -> {
                    p.name = in.getText().toString().trim();
                    pinsChanged();
                })
                .setNeutralButton("Hapus titik", (d, w) -> {
                    pins.remove(p);
                    pinsChanged();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void pinsChanged() {
        map.setPins(pins);
        savePins();
        updateEditHint();
    }

    private void loadPins() {
        pins.clear();
        try {
            JSONArray a = new JSONArray(prefs().getString("pins", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                MapView.Pin p = new MapView.Pin();
                p.lon = o.getDouble("lon");
                p.lat = o.getDouble("lat");
                p.name = o.optString("name");
                p.time = o.optLong("t");
                p.acc = (float) o.optDouble("acc", 0);
                pins.add(p);
            }
        } catch (JSONException ignored) {
        }
    }

    private void savePins() {
        JSONArray a = new JSONArray();
        try {
            for (MapView.Pin p : pins) {
                a.put(new JSONObject().put("lon", p.lon).put("lat", p.lat).put("name", p.name).put("t", p.time).put("acc", p.acc));
            }
        } catch (JSONException ignored) {
        }
        prefs().edit().putString("pins", a.toString()).apply();
    }

    private void askSave() {
        if (busy) return;
        if (pins.isEmpty()) { toast("Belum ada titik. Ketuk peta untuk menambah titik dulu."); return; }
        List<GeoFile.Layer> visible = visibleLayers();
        boolean hasPdf = false;
        for (GeoFile.Layer l : visible) if (l.image != null) hasPdf = true;
        String def = "Peta baru " + new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(new Date());
        EditText in = Ui.textInput(this, "Nama peta", def);
        in.selectAll();
        LinearLayout body = Ui.dialogBody(this);
        body.addView(Ui.label(this, (visible.isEmpty() ? "Isi: " + pins.size() + " titik saja (tidak ada peta yang tampil)."
                : "Isi: " + visible.size() + " lapisan yang tampil + " + pins.size() + " titik.")
                + "\nFormat: " + (hasPdf ? "GeoPDF (.pdf), titik digambar di atas peta PDF." : "GeoJSON (.geojson).")
                + "\nDisimpan di aplikasi dan di Download/ESP32Hub/Peta/."));
        body.addView(in);
        CheckBox pts = new CheckBox(this);
        pts.setText("Simpan juga titik saja sebagai GeoJSON (nama, lat, lon, waktu)");
        pts.setTextColor(Ui.color(this, R.color.text));
        pts.setChecked(prefs().getBoolean("ptsJson", true));
        body.addView(pts, Ui.margins(this, 0, 8, 0, 0));
        new AlertDialog.Builder(this)
                .setTitle("Simpan sebagai peta baru")
                .setView(body)
                .setPositiveButton("Simpan", (d, w) -> {
                    prefs().edit().putBoolean("ptsJson", pts.isChecked()).apply();
                    save(in.getText().toString().trim().isEmpty() ? def : in.getText().toString().trim(), visible, pts.isChecked());
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void save(String name, List<GeoFile.Layer> visible, boolean pointsJson) {
        busy = true;
        List<MapView.Pin> snapshot = new ArrayList<>(pins);
        sub.setText("Menyimpan " + name + "…");
        toast("Menyimpan peta…");
        io.execute(() -> {
            String err = null, saved = null, savedPts = null;
            GeoFile.Layer layer = null;
            int outside = 0;
            try {
                MapExport.Result r = MapExport.export(visible, snapshot);
                outside = r.outside;
                String file = Downloader.safeName(name) + r.ext;
                layer = storeAndRead(file, r.data);   // dibaca ulang: sekaligus memastikan file baru valid
                try {
                    saved = MapExport.saveToDownloads(this, file, r.mime, r.data);
                } catch (IOException | RuntimeException e) {
                    saved = null;
                    err = "Tersimpan di aplikasi, tapi gagal menyalin ke Download: " + e.getMessage();
                }
                if (pointsJson) {
                    // titik saja, supaya koordinatnya bisa dipakai lagi (QGIS, Excel, aplikasi lain) walau peta dasarnya PDF
                    try {
                        MapExport.Result p = MapExport.geoJson(new ArrayList<>(), snapshot);
                        savedPts = MapExport.saveToDownloads(this, Downloader.safeName(name) + " - titik.geojson", p.mime, p.data);
                    } catch (IOException | RuntimeException e) {
                        err = (err == null ? "" : err + "\n") + "GeoJSON titik gagal disimpan: " + e.getMessage();
                    }
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                err = "Gagal menyimpan: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            }
            GeoFile.Layer l = layer;
            String fe = err, fs = saved, fp = savedPts;
            int fo = outside;
            main.post(() -> {
                busy = false;
                if (isFinishing()) return;
                if (l == null) {
                    updateSub();
                    new AlertDialog.Builder(this).setTitle("Peta tidak tersimpan").setMessage(fe).setPositiveButton("OK", null).show();
                    return;
                }
                // peta baru sudah berisi lapisan yang tampil + titik: sembunyikan yang lama agar tidak dobel
                for (GeoFile.Layer v : visible) hidden.add(v.file);
                saveHidden();
                layers.add(l);
                pins.removeAll(snapshot);
                pinsChanged();
                applyLayers();
                releaseHiddenImages();
                setEditMode(false);
                StringBuilder msg = new StringBuilder();
                msg.append("Peta baru \"").append(l.name).append("\" sudah ditambahkan sebagai lapisan.");
                if (fs != null) msg.append("\n\nSalinan: Download/ESP32Hub/Peta/").append(fs);
                if (fp != null) msg.append("\n\nData titik (GeoJSON): Download/ESP32Hub/Peta/").append(fp);
                if (fe != null) msg.append("\n\n").append(fe);
                if (!visible.isEmpty()) msg.append("\n\nLapisan lama disembunyikan (bisa ditampilkan lagi lewat Lapisan).");
                if (fo > 0) msg.append("\n\n").append(fo).append(" titik di luar area peta PDF tidak ikut tergambar.");
                new AlertDialog.Builder(this).setTitle("Peta tersimpan").setMessage(msg).setPositiveButton("OK", null).show();
            });
        });
    }

    // ------------------------------------------------------------------ lokasi HP

    private final LocationListener phoneListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) { onPhoneLocation(loc); }
        // wajib di Android 10 (belum ada implementasi bawaan)
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { updatePhoneStatus(); }
        @Override public void onProviderDisabled(String provider) { updatePhoneStatus(); }
    };

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startPhone() {
        if (listening) return;
        if (!hasLocationPermission()) {
            if (!locAsked) {
                locAsked = true;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            }
            updatePhoneStatus();
            return;
        }
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.getAllProviders().contains(p)) continue;
                Location last = lm.getLastKnownLocation(p);
                if (last != null && (phoneFix == null || last.getTime() > phoneFix.getTime())) {
                    phoneFix = last;
                    map.setPhone(last.getLongitude(), last.getLatitude(), last.hasAccuracy() ? last.getAccuracy() : 0, false);
                }
                lm.requestLocationUpdates(p, 2000, 0, phoneListener, Looper.getMainLooper());
                listening = true;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            listening = false;
        }
        updatePhoneStatus();
    }

    private void stopPhone() {
        if (!listening) return;
        try { lm.removeUpdates(phoneListener); } catch (RuntimeException ignored) { }
        listening = false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_LOC) return;
        if (hasLocationPermission() && resumed) startPhone();
        else updatePhoneStatus();
    }

    private void onPhoneLocation(Location loc) {
        long now = System.currentTimeMillis();
        boolean fresh = phoneFix != null && now - phoneFixAt < 10000;
        // fix jaringan yang lebih kasar tidak menimpa fix GPS yang masih baru
        if (fresh && LocationManager.NETWORK_PROVIDER.equals(loc.getProvider())
                && LocationManager.GPS_PROVIDER.equals(phoneFix.getProvider())
                && loc.getAccuracy() > phoneFix.getAccuracy()) return;
        phoneFix = loc;
        phoneFixAt = now;
        map.setPhone(loc.getLongitude(), loc.getLatitude(), loc.hasAccuracy() ? loc.getAccuracy() : 0, true);
        updatePhoneStatus();
    }

    private void updatePhoneStatus() {
        updateEditHint();  // tombol Tambah titik ikut akurasi terbaru
        if (!hasLocationPermission()) {
            phoneStatus.setText("HP: izin lokasi belum diberikan (Pengaturan → Aplikasi → ESP32 Hub → Izin → Lokasi)");
            return;
        }
        if (lm != null && !lm.isLocationEnabled()) {
            phoneStatus.setText("HP: lokasi HP mati. Nyalakan Lokasi/GPS di pengaturan HP.");
            phoneStatus.setOnClickListener(v -> {
                try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (RuntimeException ignored) { }
            });
            return;
        }
        phoneStatus.setOnClickListener(null);
        if (phoneFix == null) {
            phoneStatus.setText("HP: mencari sinyal GPS… (di tempat terbuka lebih cepat)");
            return;
        }
        boolean live = System.currentTimeMillis() - phoneFixAt < PHONE_STALE_MS;
        String acc = phoneFix.hasAccuracy() ? String.format(Locale.US, " · ±%.0f m", phoneFix.getAccuracy()) : "";
        phoneStatus.setText("HP: " + coord(phoneFix.getLatitude(), phoneFix.getLongitude()) + acc
                + (live ? "" : " · terakhir " + ago(System.currentTimeMillis() - phoneFix.getTime())));
    }

    /** Tombol Lokasi: sensor bila sedang menerima posisi, kalau tidak lokasi HP. */
    private void goToLocation() {
        double[] g = map.gps();
        double[] p = map.phone();
        if (g != null && map.gpsLive()) map.centerOn(g[0], g[1]);
        else if (p != null) map.centerOn(p[0], p[1]);
        else if (g != null) map.centerOn(g[0], g[1]);
        else toast("Belum ada lokasi sensor maupun HP");
    }

    // ------------------------------------------------------------------ GPS dari ESP

    private SharedPreferences prefs() { return getSharedPreferences("map", MODE_PRIVATE); }

    private void restoreLastFix() {
        SharedPreferences p = prefs();
        if (p.contains("lat")) {
            double lat = Double.longBitsToDouble(p.getLong("lat", 0)), lon = Double.longBitsToDouble(p.getLong("lon", 0));
            map.setGps(lon, lat, false);
        }
        sensorStatus.setText(lastText());
    }

    private String lastText() {
        SharedPreferences p = prefs();
        if (!p.contains("lat")) return "Sensor: tidak tersambung (sambungkan ESP lewat Buka di layar Download)";
        double lat = Double.longBitsToDouble(p.getLong("lat", 0)), lon = Double.longBitsToDouble(p.getLong("lon", 0));
        return "Sensor: tidak tersambung · terakhir " + p.getString("esp", "ESP") + " " + coord(lat, lon)
                + " · " + ago(System.currentTimeMillis() - p.getLong("at", 0));
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed || isFinishing()) { polling = false; return; }
            if (phoneFix != null) {
                map.setPhoneLive(System.currentTimeMillis() - phoneFixAt < PHONE_STALE_MS);
                updatePhoneStatus();
            }
            EspLink link = dl.currentLink();
            if (link == null) {
                map.setGpsLive(false);
                sensorStatus.setText(lastText());
                main.postDelayed(this, POLL_MS);
                return;
            }
            Store.Esp esp = Store.get(MapActivity.this).find(dl.connectedKey());
            String espId = esp != null ? esp.id : "ESP";
            try {
                io.execute(() -> {
                    long next = POLL_MS;
                    String text = null, err = null;
                    int code = 0;
                    try {
                        HttpURLConnection h = link.http("/gps");
                        h.setConnectTimeout(3000);
                        h.setReadTimeout(3000);
                        try {
                            code = h.getResponseCode();
                            if (code == 200) text = EspLink.readAll(h.getInputStream());
                        } finally {
                            h.disconnect();
                        }
                    } catch (IOException e) {
                        err = e.getMessage();
                    }
                    if (code == 404) next = POLL_SLOW_MS;
                    String ft = text, fe = err;
                    int fc = code;
                    long fn = next;
                    main.post(() -> {
                        onGps(espId, fc, ft, fe);
                        if (resumed && !isFinishing()) main.postDelayed(poll, fn);
                        else polling = false;
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                polling = false;
            }
        }
    };

    private void onGps(String espId, int code, String text, String err) {
        if (code == 404) {
            map.setGpsLive(false);
            sensorStatus.setText("Sensor: " + espId + " tersambung, tapi firmware-nya belum punya /gps (upload firmware EspHub terbaru)");
            return;
        }
        if (text == null) {
            map.setGpsLive(false);
            sensorStatus.setText("Sensor: " + espId + " GPS tidak terbaca" + (err != null ? " (" + err + ")" : code != 0 ? " (ESP menjawab " + code + ")" : ""));
            return;
        }
        try {
            JSONObject o = new JSONObject(text);
            int sats = o.optInt("sats", -1);
            boolean fix = o.optBoolean("fix", o.has("lat"));
            if (!fix || !o.has("lat") || !o.has("lon")) {
                map.setGpsLive(false);
                sensorStatus.setText("Sensor: " + espId + " menunggu sinyal GPS" + (sats >= 0 ? " (" + sats + " satelit)" : ""));
                return;
            }
            double lat = o.getDouble("lat"), lon = o.getDouble("lon");
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                sensorStatus.setText("Sensor: " + espId + " koordinat GPS tidak valid");
                return;
            }
            map.setGps(lon, lat, true);
            prefs().edit()
                    .putLong("lat", Double.doubleToRawLongBits(lat))
                    .putLong("lon", Double.doubleToRawLongBits(lon))
                    .putLong("at", System.currentTimeMillis())
                    .putString("esp", espId)
                    .apply();
            StringBuilder s = new StringBuilder("Sensor: ").append(espId).append(" ").append(coord(lat, lon));
            if (sats >= 0) s.append(" · ").append(sats).append(" satelit");
            if (o.has("hdop")) s.append(String.format(Locale.US, " · HDOP %.1f", o.optDouble("hdop")));
            sensorStatus.setText(s);
        } catch (JSONException e) {
            map.setGpsLive(false);
            sensorStatus.setText("Sensor: " + espId + " jawaban /gps bukan JSON yang dikenal");
        }
    }

    static String coord(double lat, double lon) {
        return String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " dtk lalu";
        if (s < 3600) return s / 60 + " mnt lalu";
        if (s < 86400) return s / 3600 + " jam lalu";
        return s / 86400 + " hari lalu";
    }


    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
