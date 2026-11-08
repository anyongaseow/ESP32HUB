package com.esphub;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/** Panduan pemakaian bergambar, dari assets/panduan.html (tanpa internet). */
public class GuideActivity extends Activity {

    private WebView web;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration c = MainActivity.themeOverride(base);
        if (c != null) applyOverrideConfiguration(c);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.col(this);
        root.setFitsSystemWindows(true);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        header.addView(Ui.iconButton(this, R.drawable.ic_back, "Kembali", v -> finish()));
        header.addView(Ui.text(this, "Panduan", 18, R.color.text, true), Ui.weight(1));
        root.addView(header);

        web = new WebView(this);
        web.setBackgroundColor(Ui.color(this, R.color.bg));  // tidak berkedip putih di tema gelap
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);  // hanya untuk tema dan tombol lompat bagian
        s.setBlockNetworkLoads(true);  // panduan sepenuhnya lokal
        s.setAllowFileAccess(true);
        root.addView(web, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        setContentView(root);
        MainActivity.applyBars(this);

        boolean dark = !getResources().getBoolean(R.bool.lightBars);
        if (b != null) web.restoreState(b);
        else web.loadUrl("file:///android_asset/panduan.html" + (dark ? "#dark" : ""));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
