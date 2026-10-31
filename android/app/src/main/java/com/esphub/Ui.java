package com.esphub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Pembuat view kecil-kecilan, supaya tidak perlu library UI (APK tetap kecil). */
final class Ui {

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int res) {
        return c.getColor(res);
    }

    static GradientDrawable round(Context c, int colorRes, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(c, colorRes));
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static TextView text(Context c, String s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(c, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView oneLine(TextView t, TextUtils.TruncateAt where) {
        t.setSingleLine(true);
        t.setEllipsize(where);
        return t;
    }

    /** Tombol: primary = isi warna aksen, selain itu = garis tipis. */
    static TextView button(Context c, String s, boolean primary) {
        TextView b = text(c, s, 14, primary ? R.color.onAccent : R.color.accent, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setMinHeight(dp(c, 36));
        GradientDrawable bg = round(c, primary ? R.color.accent : R.color.card, 18);
        if (!primary) bg.setStroke(dp(c, 1), color(c, R.color.line));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(color(c, R.color.line)), bg, null));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static ProgressBar bar(Context c) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(1000);
        p.setProgressTintList(ColorStateList.valueOf(color(c, R.color.accent)));
        p.setProgressBackgroundTintList(ColorStateList.valueOf(color(c, R.color.line)));
        return p;
    }

    static EditText input(Context c, String hint, String value, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setTypeface(Typeface.DEFAULT);  // input password default-nya monospace
        e.setTextColor(color(c, R.color.text));
        e.setHintTextColor(color(c, R.color.sub));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return e;
    }

    static EditText textInput(Context c, String hint, String value) {
        return input(c, hint, value, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    }

    static TextView label(Context c, String s) {
        TextView t = text(c, s, 12, R.color.sub, false);
        t.setPadding(0, dp(c, 10), 0, 0);
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams margins(Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static View gap(Context c, int dpW) {
        View v = new View(c);
        v.setLayoutParams(lp(dp(c, dpW), 1));
        return v;
    }

    static String size(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", b / 1048576.0);
    }

    /** Padding dialog yang rapi. */
    static LinearLayout dialogBody(Context c) {
        LinearLayout l = col(c);
        l.setPadding(dp(c, 22), dp(c, 6), dp(c, 22), dp(c, 4));
        return l;
    }
}
