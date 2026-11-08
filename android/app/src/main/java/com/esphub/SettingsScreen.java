package com.esphub;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Layar Pengaturan: daftar ESP, tambah/ubah/hapus, dan kirim SSID/password baru ke ESP. */
final class SettingsScreen {

    static final String FACTORY_PASS = "12345678";

    private final MainActivity a;
    private LinearLayout listBox;
    private EditText search;

    SettingsScreen(MainActivity a) { this.a = a; }

    View build() {
        LinearLayout screen = Ui.col(a);

        LinearLayout actions = Ui.row(a);
        TextView add = Ui.button(a, "+ Tambah ESP", true);
        add.setOnClickListener(v -> edit(null));
        actions.addView(add, Ui.weight(1));
        actions.addView(Ui.gap(a, 8));
        TextView bulk = Ui.button(a, "+ Tambah banyak", false);
        bulk.setOnClickListener(v -> bulkAdd());
        actions.addView(bulk, Ui.weight(1));
        screen.addView(actions, Ui.margins(a, 16, 0, 16, 10));

        search = Ui.textInput(a, "Cari ESP…", "");
        search.setBackground(Ui.round(a, R.color.card, 12));
        search.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });
        screen.addView(search, Ui.margins(a, 16, 0, 16, 8));

        ScrollView scroll = new ScrollView(a);
        LinearLayout inner = Ui.col(a);
        inner.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 16));
        listBox = Ui.col(a);
        listBox.setBackground(Ui.round(a, R.color.card, 14));
        inner.addView(listBox);
        TextView help = Ui.text(a,
                "Tips ESP baru: SSID awal \"ESP32-xxxxxx\" (lihat daftar WiFi di HP), password awal "
                        + FACTORY_PASS + ".\nTambah ESP, centang \"Kirim juga ke ESP\", isi login awal, lalu Simpan.\n"
                        + "Lupa password ESP? Tahan tombol BOOT saat ESP dinyalakan (±3 detik) untuk reset ke awal.",
                12, R.color.sub, false);
        help.setPadding(Ui.dp(a, 4), Ui.dp(a, 14), Ui.dp(a, 4), 0);
        inner.addView(help);
        TextView guide = Ui.button(a, "Buka panduan bergambar", false);
        guide.setOnClickListener(v -> a.openGuide());
        inner.addView(guide, Ui.margins(a, 0, 12, 0, 0));

        // Tampilan jarang diubah, jadi diletakkan di bawah daftar ESP.
        TextView themeLabel = Ui.text(a, "Tampilan", 14, R.color.text, true);
        themeLabel.setPadding(Ui.dp(a, 4), Ui.dp(a, 28), 0, Ui.dp(a, 8));
        inner.addView(themeLabel);
        inner.addView(Ui.segmented(a, new String[]{"Ikuti sistem", "Terang", "Gelap"},
                MainActivity.themeMode(a), a::setThemeMode), Ui.lp(MATCH_PARENT, WRAP_CONTENT));
        scroll.addView(inner);
        screen.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        refresh();
        return screen;
    }

    private void refresh() {
        listBox.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Store.Esp> all = a.store().all();
        int shown = 0;
        for (Store.Esp e : all) {
            if (!q.isEmpty() && !e.id.toLowerCase(Locale.ROOT).contains(q)
                    && !e.ssid.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (shown++ > 0) {
                View line = new View(a);
                line.setBackgroundColor(Ui.color(a, R.color.line));
                listBox.addView(line, Ui.margins(a, 16, 0, 16, 0));
                line.getLayoutParams().height = 1;
            }
            LinearLayout row = Ui.row(a);
            row.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
            row.setBackground(Ui.pressable(a, false));
            row.setMinimumHeight(Ui.dp(a, 56));
            LinearLayout names = Ui.col(a);
            names.addView(Ui.oneLine(Ui.text(a, e.id, 15, R.color.text, true), TextUtils.TruncateAt.END));
            names.addView(Ui.oneLine(Ui.text(a, "SSID: " + e.ssid, 12, R.color.sub, false), TextUtils.TruncateAt.END));
            row.addView(names, Ui.weight(1));
            row.addView(Ui.text(a, "›", 22, R.color.sub, false));
            row.setOnClickListener(v -> edit(e));
            listBox.addView(row, Ui.lp(MATCH_PARENT, WRAP_CONTENT));
        }
        if (shown == 0) {
            TextView t = Ui.text(a, all.isEmpty() ? "Belum ada ESP" : "Tidak ditemukan", 14, R.color.sub, false);
            t.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16));
            listBox.addView(t);
        }
    }

    // ---------------------------------------------------------------- tambah / ubah satu ESP

    /** Dipanggil dari layar Download yang masih kosong. */
    void addNew() { edit(null); }

    private void edit(Store.Esp src) {
        boolean isNew = src == null;
        Store.Esp e = isNew ? new Store.Esp() : src.copy();
        if (isNew) e.id = "ESP " + nextNumber();

        LinearLayout body = Ui.dialogBody(a);
        body.addView(Ui.label(a, "ID (nama tampilan, juga nama folder unduhan)"));
        EditText id = Ui.textInput(a, "mis. ESP 1", e.id);
        body.addView(id);
        body.addView(Ui.label(a, "SSID WiFi ESP"));
        EditText ssid = Ui.textInput(a, "mis. ESP32-1", e.ssid);
        body.addView(ssid);
        body.addView(Ui.label(a, "Password WiFi ESP (8–63 karakter, kosong = tanpa password)"));
        EditText pass = Ui.input(a, "password", e.pass, MainActivity.PASSWORD);
        body.addView(pass);
        CheckBox show = new CheckBox(a);
        show.setText("Tampilkan password");
        show.setTextColor(Ui.color(a, R.color.sub));
        body.addView(show);

        CheckBox push = new CheckBox(a);
        push.setText("Kirim juga ke ESP (HP harus dekat ESP)");
        push.setTextColor(Ui.color(a, R.color.text));
        body.addView(push);

        LinearLayout cur = Ui.col(a);
        cur.addView(Ui.label(a, "Login ESP saat ini (sebelum diubah)"));
        EditText curSsid = Ui.textInput(a, "SSID saat ini, mis. ESP32-A1B2C3", isNew ? "" : e.ssid);
        cur.addView(curSsid);
        EditText curPass = Ui.input(a, "password saat ini", isNew ? FACTORY_PASS : e.pass, MainActivity.PASSWORD);
        cur.addView(curPass);
        cur.setVisibility(View.GONE);
        body.addView(cur);

        show.setOnCheckedChangeListener((b, on) -> {
            int t = on ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : MainActivity.PASSWORD;
            for (EditText f : new EditText[]{pass, curPass}) {
                f.setInputType(t);
                f.setTypeface(android.graphics.Typeface.DEFAULT);
                f.setSelection(f.length());
            }
        });
        push.setOnCheckedChangeListener((b, on) -> cur.setVisibility(on ? View.VISIBLE : View.GONE));

        AlertDialog.Builder builder = new AlertDialog.Builder(a)
                .setTitle(isNew ? "Tambah ESP" : "Ubah ESP")
                .setView(scroll(body))
                .setPositiveButton("Simpan", null)
                .setNegativeButton("Batal", null);
        if (!isNew) builder.setNeutralButton("Hapus", null);
        AlertDialog d = builder.show();

        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Store.Esp n = e.copy();
            n.id = id.getText().toString().trim();
            n.ssid = ssid.getText().toString().trim();
            n.pass = pass.getText().toString();
            String err = validate(n);
            if (err == null && push.isChecked() && curSsid.getText().toString().trim().isEmpty())
                err = "Isi SSID ESP saat ini";
            // banyak firmware (mis. Aspol) diam-diam menolak password < 8 karakter
            if (err == null && push.isChecked() && n.pass.length() < 8)
                err = "Untuk dikirim ke ESP, password minimal 8 karakter";
            if (err != null) { a.toast(err); return; }
            if (push.isChecked()) {
                sendToEsp(n, curSsid.getText().toString().trim(), curPass.getText().toString(), d);
            } else {
                a.store().put(n);
                d.dismiss();
                refresh();
            }
        });
        if (!isNew) {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new AlertDialog.Builder(a)
                            .setMessage("Hapus " + e.id + " dari daftar?\n(Data di ESP tidak terhapus.)")
                            .setPositiveButton("Hapus", (x, y) -> {
                                a.store().remove(e.key);
                                d.dismiss();
                                refresh();
                            })
                            .setNegativeButton("Batal", null)
                            .show());
        }
    }

    private String validate(Store.Esp n) {
        if (n.id.isEmpty()) return "ID wajib diisi";
        for (Store.Esp o : a.store().all()) {
            if (o.key != n.key && o.id.equalsIgnoreCase(n.id)) return "ID \"" + n.id + "\" sudah dipakai";
        }
        int ssidBytes = n.ssid.getBytes(StandardCharsets.UTF_8).length;
        if (ssidBytes < 1 || ssidBytes > 32) return "SSID harus 1–32 karakter";
        if (!n.pass.isEmpty() && (n.pass.length() < 8 || n.pass.length() > 63)) return "Password harus 8–63 karakter";
        return null;
    }

    /** Sambung ke ESP dengan login lama, kirim ID/SSID/password baru, lalu simpan di HP. */
    private void sendToEsp(Store.Esp n, String oldSsid, String oldPass, AlertDialog editDialog) {
        if (a.downloader().isBusy()) { a.toast("Tunggu unduhan selesai dulu"); return; }
        if (a.downloader().connectedKey() != 0) a.downloader().stopAll();  // lepas ESP yang masih tersambung
        AtomicBoolean cancel = new AtomicBoolean();
        LinearLayout body = Ui.dialogBody(a);
        LinearLayout r = Ui.row(a);
        r.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 8));
        r.addView(new ProgressBar(a), Ui.lp(Ui.dp(a, 36), Ui.dp(a, 36)));
        r.addView(Ui.gap(a, 14));
        r.addView(Ui.text(a, "Menyambung ke \"" + oldSsid + "\"…\nBila Android bertanya, pilih Hubungkan.", 14, R.color.text, false), Ui.weight(1));
        body.addView(r);
        AlertDialog prog = new AlertDialog.Builder(a)
                .setTitle("Mengirim ke ESP")
                .setView(body)
                .setCancelable(false)
                .setNegativeButton("Batal", (x, y) -> cancel.set(true))
                .show();

        a.withPermission(() -> new Thread(() -> {
            String err = null;
            try (EspLink link = EspLink.open(a, oldSsid, oldPass, cancel)) {
                // "pass" untuk firmware EspHub, "password" untuk firmware lain (mis. Aspol)
                link.postForm("/config", "id", n.id, "ssid", n.ssid, "pass", n.pass, "password", n.pass);
            } catch (IOException ex) {
                err = ex.getMessage();
            }
            String fe = err;
            a.runOnUiThread(() -> {
                if (prog.isShowing()) prog.dismiss();
                if (cancel.get()) return;
                if (fe == null) {
                    a.store().put(n);
                    editDialog.dismiss();
                    refresh();
                    a.toast("Tersimpan. ESP restart dengan WiFi baru.");
                } else {
                    new AlertDialog.Builder(a).setTitle("Gagal mengirim").setMessage(fe)
                            .setPositiveButton("OK", null).show();
                }
            });
        }).start());
    }

    // ---------------------------------------------------------------- tambah banyak sekaligus

    private void bulkAdd() {
        int start = nextNumber();
        LinearLayout body = Ui.dialogBody(a);
        body.addView(Ui.label(a, "Awalan ID"));
        EditText idPrefix = Ui.textInput(a, "ESP ", "ESP ");
        body.addView(idPrefix);
        body.addView(Ui.label(a, "Awalan SSID"));
        EditText ssidPrefix = Ui.textInput(a, "ESP32-", "ESP32-");
        body.addView(ssidPrefix);
        LinearLayout nums = Ui.row(a);
        LinearLayout c1 = Ui.col(a), c2 = Ui.col(a);
        c1.addView(Ui.label(a, "Mulai nomor"));
        EditText from = Ui.input(a, "1", String.valueOf(start), MainActivity.NUMBER);
        c1.addView(from);
        c2.addView(Ui.label(a, "Jumlah"));
        EditText count = Ui.input(a, "10", "10", MainActivity.NUMBER);
        c2.addView(count);
        nums.addView(c1, Ui.weight(1));
        nums.addView(Ui.gap(a, 12));
        nums.addView(c2, Ui.weight(1));
        body.addView(nums);
        body.addView(Ui.label(a, "Password (sama untuk semua)"));
        EditText pass = Ui.input(a, "password", "", MainActivity.PASSWORD);
        body.addView(pass);
        TextView preview = Ui.text(a, "", 12, R.color.sub, false);
        preview.setPadding(0, Ui.dp(a, 10), 0, 0);
        body.addView(preview);

        Runnable updatePreview = () -> {
            int f = parse(from, 1), c = parse(count, 0);
            preview.setText(c <= 0 ? "" : "Contoh: " + idPrefix.getText() + f + " → SSID " + ssidPrefix.getText() + f
                    + (c > 1 ? "\n… sampai " + idPrefix.getText() + (f + c - 1) : ""));
        };
        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void afterTextChanged(Editable s) { updatePreview.run(); }
        };
        idPrefix.addTextChangedListener(w);
        ssidPrefix.addTextChangedListener(w);
        from.addTextChangedListener(w);
        count.addTextChangedListener(w);
        updatePreview.run();

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Tambah banyak ESP")
                .setView(scroll(body))
                .setPositiveButton("Tambah", null)
                .setNegativeButton("Batal", null)
                .show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int f = parse(from, -1), c = parse(count, 0);
            String p = pass.getText().toString();
            if (f < 0) { a.toast("Nomor awal tidak valid"); return; }
            if (c < 1 || c > 1000) { a.toast("Jumlah 1–1000"); return; }
            if (!p.isEmpty() && (p.length() < 8 || p.length() > 63)) { a.toast("Password harus 8–63 karakter"); return; }
            Set<String> used = new HashSet<>();
            for (Store.Esp o : a.store().all()) used.add(o.id.toLowerCase(Locale.ROOT));
            List<Store.Esp> items = new ArrayList<>();
            int skipped = 0;
            for (int i = f; i < f + c; i++) {
                Store.Esp e = new Store.Esp();
                e.id = (idPrefix.getText().toString() + i).trim();
                e.ssid = (ssidPrefix.getText().toString() + i).trim();
                e.pass = p;
                if (!used.add(e.id.toLowerCase(Locale.ROOT)) || e.ssid.getBytes(StandardCharsets.UTF_8).length > 32) {
                    skipped++;
                    continue;
                }
                items.add(e);
            }
            a.store().putAll(items);
            d.dismiss();
            refresh();
            a.toast(items.size() + " ESP ditambahkan" + (skipped > 0 ? ", " + skipped + " dilewati (ID sudah ada)" : ""));
        });
    }

    // ---------------------------------------------------------------- bantuan

    /** Nomor berikutnya = angka terbesar di akhir ID + 1. */
    private int nextNumber() {
        int max = 0;
        Pattern p = Pattern.compile("(\\d+)\\s*$");
        for (Store.Esp e : a.store().all()) {
            Matcher m = p.matcher(e.id);
            if (m.find()) {
                try { max = Math.max(max, Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) { }
            }
        }
        return max + 1;
    }

    private static int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (NumberFormatException x) { return def; }
    }

    private ScrollView scroll(View v) {
        ScrollView s = new ScrollView(a);
        s.addView(v);
        return s;
    }
}
