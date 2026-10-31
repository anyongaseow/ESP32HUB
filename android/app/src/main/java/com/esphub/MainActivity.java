package com.esphub;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class MainActivity extends Activity implements Downloader.Listener {

    private Store store;
    private Downloader dl;

    private TextView title;
    private ImageView headerBtn;
    private FrameLayout content;
    private boolean inSettings;
    private SettingsScreen settings;

    // layar Download
    private LinearLayout cardsBox;
    private EditText search;
    private TextView summary, allBtn, empty;
    private final Map<Long, Card> cards = new HashMap<>();
    private final Set<Long> collapsed = new HashSet<>();

    private Runnable afterPermission;

    static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    static int themeMode(Context c) {
        return c.getSharedPreferences("ui", MODE_PRIVATE).getInt("theme", THEME_SYSTEM);
    }

    /** Ganti tema lalu muat ulang layar (tetap di layar yang sama). */
    void setThemeMode(int mode) {
        if (mode == themeMode(this)) return;
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("theme", mode).apply();
        recreate();
    }

    /** Konfigurasi untuk memaksa terang/gelap (resource values-night/ ikut terpilih), null = ikut sistem. */
    static Configuration themeOverride(Context base) {
        int mode = themeMode(base);
        if (mode == THEME_SYSTEM) return null;
        Configuration c = new Configuration();
        c.uiMode = (base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == THEME_DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return c;
    }

    /** Ikon status bar gelap di tema terang, terang di tema gelap. */
    static void applyBars(Activity a) {
        if (a.getResources().getBoolean(R.bool.lightBars)) {
            a.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration c = themeOverride(base);
        if (c != null) applyOverrideConfiguration(c);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("settings", inSettings);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        dl = Downloader.get(this);
        settings = new SettingsScreen(this);

        LinearLayout root = Ui.col(this);
        root.setFitsSystemWindows(true);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 8));
        title = Ui.text(this, "", 22, R.color.text, true);
        header.addView(title, Ui.weight(1));
        headerBtn = new ImageView(this);
        int p = Ui.dp(this, 12);
        headerBtn.setPadding(p, p, p, p);
        headerBtn.setBackground(getDrawable(android.R.drawable.list_selector_background));
        headerBtn.setOnClickListener(v -> { if (inSettings) showDownloads(); else showSettings(); });
        header.addView(headerBtn, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(header);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        setContentView(root);
        applyBars(this);

        if (b != null && b.getBoolean("settings")) showSettings();
        else showDownloads();
    }

    @Override
    protected void onResume() {
        super.onResume();
        dl.setListener(this);
        if (!inSettings) refreshAll();
        onBusy(dl.isBusy());
    }

    @Override
    protected void onDestroy() {
        dl.clearListener(this);  // saat recreate(), activity baru mungkin sudah terdaftar
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (inSettings) showDownloads();
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- layar

    private void showSettings() {
        inSettings = true;
        title.setText("Pengaturan");
        headerBtn.setImageResource(R.drawable.ic_back);
        headerBtn.setContentDescription("Kembali");
        content.removeAllViews();
        content.addView(settings.build());
    }

    void showDownloads() {
        inSettings = false;
        title.setText("Download");
        headerBtn.setImageResource(R.drawable.ic_settings);
        headerBtn.setContentDescription("Pengaturan");
        content.removeAllViews();

        LinearLayout screen = Ui.col(this);

        search = Ui.textInput(this, "Cari ESP…", "");
        search.setBackground(Ui.round(this, R.color.card, 12));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        screen.addView(search, Ui.margins(this, 16, 0, 16, 8));

        ScrollView scroll = new ScrollView(this);
        cardsBox = Ui.col(this);
        cardsBox.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        scroll.addView(cardsBox);
        screen.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        empty = Ui.text(this, "Belum ada ESP.\nTambahkan lewat tombol ⚙ di kanan atas.", 15, R.color.sub, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 48), 0, 0);
        cardsBox.addView(empty);

        LinearLayout bottom = Ui.row(this);
        bottom.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 12));
        bottom.setBackgroundColor(Ui.color(this, R.color.card));
        summary = Ui.text(this, "", 13, R.color.sub, false);
        bottom.addView(summary, Ui.weight(1));
        allBtn = Ui.button(this, "Unduh semua", true);
        allBtn.setOnClickListener(v -> {
            if (dl.isBusy()) dl.stopAll();
            else downloadAllVisible();
        });
        bottom.addView(allBtn);
        screen.addView(bottom, Ui.lp(MATCH_PARENT, WRAP_CONTENT));

        content.addView(screen);

        cards.clear();
        for (Store.Esp e : store.all()) {
            Card c = new Card(e);
            cards.put(e.key, c);
            cardsBox.addView(c.root, Ui.margins(this, 0, 6, 0, 6));
            c.bind(e, dl.job(e.key));
        }
        applyFilter();
        onBusy(dl.isBusy());
    }

    private void refreshAll() {
        for (Store.Esp e : store.all()) {
            Card c = cards.get(e.key);
            if (c != null) c.bind(e, dl.job(e.key));
        }
        updateSummary();
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Store.Esp e : store.all()) {
            Card c = cards.get(e.key);
            if (c == null) continue;
            boolean match = q.isEmpty() || e.id.toLowerCase(Locale.ROOT).contains(q)
                    || e.ssid.toLowerCase(Locale.ROOT).contains(q);
            c.root.setVisibility(match ? View.VISIBLE : View.GONE);
            if (match) shown++;
        }
        empty.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        allBtn.setTag(shown);
        updateSummary();
    }

    private List<Long> visibleKeys() {
        List<Long> out = new ArrayList<>();
        for (Store.Esp e : store.all()) {
            Card c = cards.get(e.key);
            if (c != null && c.root.getVisibility() == View.VISIBLE) out.add(e.key);
        }
        return out;
    }

    private void downloadAllVisible() {
        List<Long> keys = visibleKeys();
        if (keys.isEmpty()) { toast("Tidak ada ESP untuk diunduh"); return; }
        withPermission(() -> { for (Long k : keys) dl.fullDownload(k); });
    }

    private void updateSummary() {
        if (summary == null || inSettings) return;
        int total = cards.size();
        long ck = dl.connectedKey();
        Store.Esp connected = ck != 0 ? store.find(ck) : null;
        if (dl.isBusy()) {
            int left = dl.pendingFull();
            summary.setText(left > 0 ? "Mengunduh… sisa " + left + " ESP antri" : "Sedang bekerja…");
            allBtn.setText("Batalkan");
            allBtn.setEnabled(true);
            allBtn.setAlpha(1f);
        } else {
            Object shown = allBtn.getTag();
            int n = shown instanceof Integer ? (Integer) shown : total;
            summary.setText(connected != null ? "Tersambung ke " + connected.id : total + " ESP terdaftar");
            allBtn.setText(n == total ? "Unduh semua" : "Unduh " + n + " ESP");
            allBtn.setEnabled(n > 0);
            allBtn.setAlpha(n > 0 ? 1f : 0.4f);
        }
    }

    // ---------------------------------------------------------------- Downloader.Listener

    @Override
    public void onJob(long key) {
        if (inSettings) return;
        Card c = cards.get(key);
        Store.Esp e = store.find(key);
        if (c != null && e != null) c.bind(e, dl.job(key));
        updateSummary();
    }

    @Override
    public void onBusy(boolean busy) {
        if (busy || dl.connectedKey() != 0) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateSummary();
    }

    // ---------------------------------------------------------------- izin

    /** Minta izin WiFi bila perlu, lalu jalankan aksi (tetap dicoba walau izin ditolak). */
    void withPermission(Runnable action) {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        afterPermission = action;
        String[] req = Build.VERSION.SDK_INT >= 33
                ? new String[]{perm}
                : new String[]{perm, Manifest.permission.ACCESS_COARSE_LOCATION};
        requestPermissions(req, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) toast("Tanpa izin ini, Android mungkin menolak sambungan ke ESP");
        Runnable r = afterPermission;
        afterPermission = null;
        if (r != null) r.run();
    }

    static final String SHEETS = "com.google.android.apps.docs.editors.sheets";

    /** Buka CSV di Google Sheets; bila tidak terpasang, pengguna memilih aplikasi lain. */
    void openCsv(Downloader.FileState f) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(f.saved, "text/csv")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(new Intent(view).setPackage(SHEETS));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(Intent.createChooser(view, "Buka " + Downloader.shortName(f.path) + " dengan"));
            } catch (ActivityNotFoundException e2) {
                toast("Tidak ada aplikasi untuk membuka CSV. Pasang Google Sheets dari Play Store.");
            }
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- kartu per ESP

    private final class Card {
        final long key;
        final LinearLayout root, filesBox;
        final TextView name, ssid, chip, msg, btn, allFilesBtn, webBtn;
        final LinearLayout actions;
        final List<FileRow> rows = new ArrayList<>();

        Card(Store.Esp e) {
            key = e.key;
            root = Ui.col(MainActivity.this);
            root.setBackground(Ui.round(MainActivity.this, R.color.card, 16));
            int p = Ui.dp(MainActivity.this, 14);
            root.setPadding(p, p, p, p);

            LinearLayout top = Ui.row(MainActivity.this);
            LinearLayout names = Ui.col(MainActivity.this);
            name = Ui.oneLine(Ui.text(MainActivity.this, "", 16, R.color.text, true), TextUtils.TruncateAt.END);
            ssid = Ui.oneLine(Ui.text(MainActivity.this, "", 12, R.color.sub, false), TextUtils.TruncateAt.END);
            names.addView(name);
            names.addView(ssid);
            top.addView(names, Ui.weight(1));
            chip = Ui.text(MainActivity.this, "", 12, R.color.sub, true);
            chip.setPadding(Ui.dp(MainActivity.this, 8), 0, Ui.dp(MainActivity.this, 8), 0);
            top.addView(chip);
            btn = Ui.button(MainActivity.this, "Buka", false);
            btn.setOnClickListener(v -> {
                if (dl.connectedKey() == key) dl.stopAll();
                else withPermission(() -> dl.open(key));
            });
            top.addView(btn);
            root.addView(top);
            names.setOnClickListener(v -> {
                if (!collapsed.remove(key)) collapsed.add(key);
                Store.Esp cur = store.find(key);
                if (cur != null) bind(cur, dl.job(key));
            });

            msg = Ui.text(MainActivity.this, "", 12, R.color.sub, false);
            msg.setPadding(0, Ui.dp(MainActivity.this, 6), 0, 0);
            root.addView(msg);

            filesBox = Ui.col(MainActivity.this);
            root.addView(filesBox);

            actions = Ui.row(MainActivity.this);
            webBtn = Ui.button(MainActivity.this, "Buka web", false);
            webBtn.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, BrowserActivity.class)));
            actions.addView(webBtn, Ui.weight(1));
            actions.addView(Ui.gap(MainActivity.this, 8));
            allFilesBtn = Ui.button(MainActivity.this, "", true);
            allFilesBtn.setOnClickListener(v -> withPermission(() -> dl.downloadAllFiles(key)));
            actions.addView(allFilesBtn, Ui.weight(1));
            root.addView(actions, Ui.margins(MainActivity.this, 0, 12, 0, 0));
        }

        void bind(Store.Esp e, Downloader.Job j) {
            name.setText(e.id);
            List<Downloader.FileState> files = j.files;
            boolean show = !collapsed.contains(key);
            String fileCount = files.isEmpty() ? "" : "  ·  " + files.size() + " file" + (show ? "  ▾" : "  ▸");
            ssid.setText(e.ssid + fileCount);

            long sum = 0, done = 0;
            int notDone = 0;
            for (Downloader.FileState f : files) {
                if (f.size > 0) sum += f.size;
                done += f.done;
                if (f.st == Downloader.F_WAIT || f.st == Downloader.F_ERR) notDone++;
            }
            int pct = sum > 0 ? (int) Math.min(100, done * 100 / sum) : 0;

            boolean connected = dl.connectedKey() == key;
            switch (j.st) {
                case Downloader.QUEUED: chip("Antri", R.color.warn); break;
                case Downloader.CONNECTING: chip("Menyambung", R.color.accent); break;
                case Downloader.LISTING: chip("Membaca", R.color.accent); break;
                case Downloader.READY: chip("Tersambung", R.color.ok); break;
                case Downloader.DOWNLOADING: chip(dl.pendingFull() > 0 || !connected ? pct + "%" : "Mengunduh", R.color.accent); break;
                case Downloader.DONE: chip("Selesai", R.color.ok); break;
                case Downloader.ERROR: chip("Gagal", R.color.err); break;
                case Downloader.CANCELLED: chip("Batal", R.color.sub); break;
                default: chip("", R.color.sub);
            }
            msg.setText(j.msg);
            msg.setVisibility(j.msg.isEmpty() ? View.GONE : View.VISIBLE);
            msg.setTextColor(Ui.color(MainActivity.this, j.st == Downloader.ERROR ? R.color.err : R.color.sub));

            boolean waiting = !connected && j.active();  // sedang antri / menyambung
            btn.setText(connected ? "Putus" : "Buka");
            btn.setEnabled(!waiting);
            btn.setAlpha(waiting ? 0.4f : 1f);

            filesBox.setVisibility(show && !files.isEmpty() ? View.VISIBLE : View.GONE);
            boolean canAll = show && notDone > 0 && j.st != Downloader.DOWNLOADING;
            allFilesBtn.setText("Unduh semua (" + notDone + ")");
            allFilesBtn.setVisibility(canAll ? View.VISIBLE : View.GONE);
            webBtn.setVisibility(connected ? View.VISIBLE : View.GONE);
            actions.setVisibility(canAll || connected ? View.VISIBLE : View.GONE);
            if (!show) return;
            if (rows.size() != files.size()) {
                filesBox.removeAllViews();
                rows.clear();
                for (int i = 0; i < files.size(); i++) {
                    FileRow r = new FileRow(key);
                    rows.add(r);
                    filesBox.addView(r.root, Ui.margins(MainActivity.this, 0, 4, 0, 0));
                }
            }
            for (int i = 0; i < files.size(); i++) rows.get(i).bind(files.get(i));
        }

        private void chip(String s, int colorRes) {
            chip.setText(s);
            chip.setTextColor(Ui.color(MainActivity.this, colorRes));
            chip.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** Satu baris file: ketuk untuk mengunduh. */
    private final class FileRow {
        final long key;
        final LinearLayout root = Ui.col(MainActivity.this);
        final TextView name = Ui.oneLine(Ui.text(MainActivity.this, "", 14, R.color.text, false), TextUtils.TruncateAt.MIDDLE);
        final TextView info = Ui.text(MainActivity.this, "", 12, R.color.sub, false);
        final TextView action = Ui.text(MainActivity.this, "", 13, R.color.accent, true);
        final ProgressBar bar = Ui.bar(MainActivity.this);
        Downloader.FileState file;

        FileRow(long key) {
            this.key = key;
            int p = Ui.dp(MainActivity.this, 8);
            root.setPadding(p, p, p, p);
            root.setBackground(getDrawable(android.R.drawable.list_selector_background));
            LinearLayout line = Ui.row(MainActivity.this);
            LinearLayout texts = Ui.col(MainActivity.this);
            texts.addView(name);
            texts.addView(info);
            line.addView(texts, Ui.weight(1));
            action.setPadding(Ui.dp(MainActivity.this, 10), 0, 0, 0);
            line.addView(action);
            root.addView(line);
            root.addView(bar, Ui.margins(MainActivity.this, 0, 6, 0, 0));
            bar.getLayoutParams().height = Ui.dp(MainActivity.this, 6);
            // Ketuk: file sudah ada -> buka (Google Sheets), belum -> unduh. Tekan lama: unduh ulang.
            root.setOnClickListener(v -> {
                Downloader.FileState f = file;
                if (f == null) return;
                if (f.st == Downloader.F_OK && f.saved != null) openCsv(f);
                else withPermission(() -> dl.downloadFile(key, f));
            });
            root.setOnLongClickListener(v -> {
                Downloader.FileState f = file;
                if (f == null || f.st == Downloader.F_QUEUED || f.st == Downloader.F_RUN) return false;
                toast("Mengunduh ulang " + Downloader.shortName(f.path));
                withPermission(() -> dl.downloadFile(key, f));
                return true;
            });
        }

        void bind(Downloader.FileState f) {
            file = f;
            name.setText(Downloader.shortName(f.path));
            int permille = f.size > 0 ? (int) Math.min(1000, f.done * 1000 / f.size) : (f.st == Downloader.F_OK ? 1000 : 0);
            bar.setProgress(permille);
            bar.setVisibility(f.st == Downloader.F_RUN ? View.VISIBLE : View.GONE);
            boolean busy = f.st == Downloader.F_QUEUED || f.st == Downloader.F_RUN;
            root.setClickable(!busy);
            switch (f.st) {
                case Downloader.F_QUEUED:
                    info.setText(Ui.size(f.size));
                    action(" Antri", R.color.warn);
                    break;
                case Downloader.F_RUN:
                    info.setText(Ui.size(f.done) + " / " + Ui.size(f.size));
                    action(permille / 10 + "%", R.color.accent);
                    break;
                case Downloader.F_OK:
                    info.setText(Ui.size(f.done) + " · " + (f.savedName != null ? f.savedName : "tersimpan"));
                    action("✓ Buka", R.color.ok);
                    break;
                case Downloader.F_ERR:
                    info.setText(Ui.size(f.size) + " · gagal");
                    action("Ulangi", R.color.err);
                    break;
                default:
                    info.setText(Ui.size(f.size));
                    action("Unduh", R.color.accent);
            }
        }

        private void action(String s, int colorRes) {
            action.setText(s.trim());
            action.setTextColor(Ui.color(MainActivity.this, colorRes));
        }
    }

    // dipakai SettingsScreen
    Store store() { return store; }
    Downloader downloader() { return dl; }

    static final int PASSWORD = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    static final int NUMBER = InputType.TYPE_CLASS_NUMBER;
}
