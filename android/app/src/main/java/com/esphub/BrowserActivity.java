package com.esphub;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/**
 * Membuka halaman web ESP yang sedang tersambung (mis. http://192.168.4.1/),
 * untuk melihat dashboard dan mengatur alat. Semua lalu lintas aplikasi diarahkan
 * ke WiFi ESP selama layar ini terbuka.
 */
public class BrowserActivity extends Activity {

    private Downloader dl;
    private ConnectivityManager cm;
    private Network net;
    private String home, espId;
    private WebView web;
    private ProgressBar bar;
    private TextView title;

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
        EspLink link = dl.currentLink();
        if (link == null) {
            Toast.makeText(this, "ESP tidak tersambung. Tekan Buka dulu.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        net = link.network();
        home = "http://" + link.host + "/";
        Store.Esp esp = Store.get(this).find(dl.connectedKey());
        espId = esp != null ? esp.id : "ESP";

        cm = getSystemService(ConnectivityManager.class);
        cm.bindProcessToNetwork(net);  // WebView tidak bisa memilih jaringan sendiri
        dl.setHold(true);              // jangan putus otomatis selama browser dibuka

        LinearLayout root = Ui.col(this);
        root.setFitsSystemWindows(true);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        header.addView(Ui.iconButton(this, R.drawable.ic_back, "Tutup", v -> finish()));
        LinearLayout names = Ui.col(this);
        title = Ui.oneLine(Ui.text(this, espId, 16, R.color.text, true), TextUtils.TruncateAt.END);
        names.addView(title);
        names.addView(Ui.oneLine(Ui.text(this, home, 12, R.color.sub, false), TextUtils.TruncateAt.END));
        header.addView(names, Ui.weight(1));
        TextView reload = Ui.button(this, "Muat ulang", false);
        reload.setOnClickListener(v -> web.reload());
        header.addView(reload);
        root.addView(header);

        bar = Ui.bar(this);
        root.addView(bar, Ui.lp(MATCH_PARENT, Ui.dp(this, 3)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);  // selalu data terbaru dari alat
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    toast("Halaman ESP tidak bisa dibuka: " + err.getDescription()
                            + "\nBila ESP baru restart (mis. setelah ganti WiFi), kembali lalu Buka lagi.");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p * 10);
                bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onReceivedTitle(WebView v, String t) {
                title.setText(TextUtils.isEmpty(t) ? espId : espId + " · " + t);
            }
        });
        web.setDownloadListener((url, ua, disposition, mime, length) -> download(url, disposition, mime));
        root.addView(web, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        setContentView(root);
        MainActivity.applyBars(this);
        web.loadUrl(home);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (cm != null) cm.bindProcessToNetwork(null);
        if (dl != null) dl.setHold(false);
        if (web != null) web.destroy();
        super.onDestroy();
    }


    /** Tombol Download di halaman ESP: simpan ke Download/ESP32Hub/&lt;ID&gt;/ dengan tanggal di nama file. */
    private void download(String url, String disposition, String mime) {
        if (!url.startsWith("http")) {
            toast("Jenis unduhan ini tidak didukung. Gunakan layar Download aplikasi.");
            return;
        }
        String name = URLUtil.guessFileName(url, disposition, mime);
        if (name.endsWith(".bin") && (mime == null || mime.contains("csv") || url.contains("download"))) {
            name = name.substring(0, name.length() - 4) + ".csv";
        }
        String base = name;
        Downloader.Job job = dl.job(dl.connectedKey());
        String fallback = job.pageDate != null ? job.pageDate : CsvSaver.today();
        toast("Mengunduh " + base + "…");
        new Thread(() -> {
            String msg;
            try {
                HttpURLConnection h = (HttpURLConnection) net.openConnection(new URL(url));
                h.setConnectTimeout(8000);
                h.setReadTimeout(20000);
                try {
                    if (h.getResponseCode() != 200) throw new IOException("ESP menjawab " + h.getResponseCode());
                    try (InputStream in = h.getInputStream()) {
                        CsvSaver.Result r = CsvSaver.save(this, espId, base, mime, in, h.getContentLengthLong(),
                                fallback, null, null);
                        msg = "Tersimpan di Download/ESP32Hub/" + Downloader.safeName(espId) + "/" + r.name;
                    }
                } finally {
                    h.disconnect();
                }
            } catch (IOException e) {
                msg = "Gagal mengunduh: " + e.getMessage();
            }
            String fm = msg;
            runOnUiThread(() -> toast(fm));
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
