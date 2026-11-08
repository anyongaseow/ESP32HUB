package com.esphub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
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

    /** Tombol: primary = isi warna aksen, selain itu = garis tipis. Tinggi 44dp supaya mudah diketuk di lapangan. */
    static TextView button(Context c, String s, boolean primary) {
        TextView b = text(c, s, 14, primary ? R.color.onAccent : R.color.accent, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        b.setMinHeight(dp(c, 44));
        GradientDrawable bg = round(c, primary ? R.color.accent : R.color.card, 10);
        if (!primary) bg.setStroke(dp(c, 1), color(c, R.color.line));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(color(c, R.color.press)), bg, null));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** Latar ripple dari tema untuk baris yang bisa diketuk (pengganti selector Holo lama). */
    static Drawable pressable(Context c, boolean borderless) {
        TypedArray a = c.obtainStyledAttributes(new int[]{borderless
                ? android.R.attr.selectableItemBackgroundBorderless
                : android.R.attr.selectableItemBackground});
        Drawable d = a.getDrawable(0);
        a.recycle();
        return d;
    }

    /** Tombol ikon 48x48dp di header. */
    static ImageView iconButton(Context c, int res, String desc, View.OnClickListener l) {
        ImageView v = new ImageView(c);
        int p = dp(c, 12);
        v.setPadding(p, p, p, p);
        v.setImageResource(res);
        v.setContentDescription(desc);
        v.setBackground(pressable(c, true));
        v.setOnClickListener(l);
        v.setLayoutParams(lp(dp(c, 48), dp(c, 48)));
        return v;
    }

    /** Label status berlatar warna status yang dipudarkan. */
    static void chip(TextView t, int colorRes) {
        int col = color(t.getContext(), colorRes);
        GradientDrawable g = new GradientDrawable();
        g.setColor((col & 0x00FFFFFF) | 0x24000000);
        g.setCornerRadius(dp(t.getContext(), 6));
        t.setBackground(g);
        t.setTextColor(col);
    }

    /** Garis pemisah 1px. */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(color(c, R.color.line));
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /** Pilihan bersebelahan (mis. Sistem / Terang / Gelap): yang aktif terisi aksen. */
    static LinearLayout segmented(Context c, String[] names, int current, java.util.function.IntConsumer pick) {
        LinearLayout box = row(c);
        GradientDrawable bg = round(c, R.color.card, 10);
        bg.setStroke(dp(c, 1), color(c, R.color.line));
        box.setBackground(bg);
        int pad = dp(c, 3);
        box.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            boolean on = i == current;
            TextView t = text(c, names[i], 14, on ? R.color.onAccent : R.color.text, on);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(c, 38));
            if (on) t.setBackground(round(c, R.color.accent, 8));
            else t.setBackground(pressable(c, false));
            t.setOnClickListener(v -> pick.accept(idx));
            box.addView(t, weight(1));
        }
        return box;
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
