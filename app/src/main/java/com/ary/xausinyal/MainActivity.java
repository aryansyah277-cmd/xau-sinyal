package com.ary.xausinyal;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final int BG = 0xFF0F1419, CARD = 0xFF1A2129, GOLD = 0xFFE8B84A, TXT = 0xFFE6E6E6, MUTED = 0xFF8A949E;
    EditText apiKey, dollar, minScore, offset;
    CheckBox useOb, siap;
    Button startStop;
    TextView summary, history;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Runner.ensureChannels(this);
        getWindow().setStatusBarColor(BG);
        SharedPreferences p = Runner.prefs(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad * 2);
        sv.addView(root);

        TextView title = text("XAU Sinyal by Ary", 24, GOLD, true);
        root.addView(title);
        root.addView(text("Notifikasi entry XAUUSD: Support/Resisten • Korelasi Dolar • Order Book", 13, MUTED, false));

        // ---- kartu ringkasan
        LinearLayout c1 = card(root);
        c1.addView(text("Analisa terakhir", 15, GOLD, true));
        summary = text(p.getString("last_summary", "Belum ada analisa."), 14, TXT, false);
        summary.setTypeface(Typeface.MONOSPACE);
        c1.addView(summary);
        Button now = button("ANALISA SEKARANG", 0xFF2D3A46);
        now.setOnClickListener(v -> analyzeNow());
        c1.addView(now);

        // ---- start / stop
        startStop = button("", GOLD);
        startStop.setTextColor(Color.BLACK);
        startStop.setOnClickListener(v -> toggle());
        root.addView(startStop, lp());

        // ---- pengaturan
        LinearLayout c2 = card(root);
        c2.addView(text("Pengaturan", 15, GOLD, true));
        c2.addView(label("API key Twelve Data (gratis di twelvedata.com)"));
        apiKey = edit(p.getString("apikey", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c2.addView(apiKey);
        c2.addView(label("Simbol pembanding dolar (EUR/USD = kebalikan DXY)"));
        dollar = edit(p.getString("dollar", "EUR/USD"), InputType.TYPE_CLASS_TEXT);
        c2.addView(dollar);
        c2.addView(label("Skor minimal untuk notifikasi (50–100, saran 70)"));
        minScore = edit(p.getString("minscore", "70"), InputType.TYPE_CLASS_NUMBER);
        c2.addView(minScore);
        c2.addView(label("Selisih harga broker vs data (mis. 0.5 atau -0.3)"));
        offset = edit(p.getString("offset", "0"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        c2.addView(offset);
        useOb = check("Pakai data order book FastBull", p.getBoolean("useob", true));
        c2.addView(useOb);
        siap = check("Kirim notifikasi \"SIAP-SIAP\" (harga mendekati level)", p.getBoolean("siap", true));
        c2.addView(siap);
        Button save = button("SIMPAN PENGATURAN", 0xFF2D3A46);
        save.setOnClickListener(v -> { save(); Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show(); });
        c2.addView(save);
        Button batt = button("IZINKAN JALAN DI LATAR BELAKANG", 0xFF2D3A46);
        batt.setOnClickListener(v -> askBattery());
        c2.addView(batt);

        // ---- riwayat
        LinearLayout c3 = card(root);
        c3.addView(text("Riwayat notifikasi", 15, GOLD, true));
        history = text("", 13, TXT, false);
        c3.addView(history);

        root.addView(text("Catatan: sinyal adalah hasil aturan analisa otomatis, bukan jaminan profit dan bukan saran keuangan. "
                + "Selalu pasang SL, pakai lot kecil, dan cek ulang di chart sebelum entry.", 12, MUTED, false));

        setContentView(sv);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        refresh();
    }

    void refresh() {
        SharedPreferences p = Runner.prefs(this);
        summary.setText(p.getString("last_summary", "Belum ada analisa."));
        String h = p.getString("history", "");
        history.setText(h.isEmpty() ? "Belum ada sinyal." : h);
        startStop.setText(SignalService.running ? "■  BERHENTI MEMANTAU" : "▶  MULAI MEMANTAU");
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(this::refresh, 5000);
    }

    void save() {
        Runner.prefs(this).edit()
                .putString("apikey", apiKey.getText().toString().trim())
                .putString("dollar", dollar.getText().toString().trim().isEmpty() ? "EUR/USD" : dollar.getText().toString().trim())
                .putString("minscore", minScore.getText().toString().trim())
                .putString("offset", offset.getText().toString().trim())
                .putBoolean("useob", useOb.isChecked())
                .putBoolean("siap", siap.isChecked())
                .apply();
    }

    void toggle() {
        save();
        Intent i = new Intent(this, SignalService.class);
        if (SignalService.running) {
            stopService(i);
            SignalService.running = false;
        } else {
            if (apiKey.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "Isi API key Twelve Data dulu", Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
        ui.postDelayed(this::refresh, 600);
    }

    void analyzeNow() {
        save();
        summary.setText("Menganalisa…");
        new Thread(() -> {
            String s = Runner.runOnce(this, false, true);
            ui.post(() -> summary.setText(s));
        }).start();
    }

    void askBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "Sudah diizinkan", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    @Override protected void onResume() { super.onResume(); if (summary != null) refresh(); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacksAndMessages(null); }

    // ---------- helper UI ----------
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        l.topMargin = dp(12);
        return l;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    TextView label(String s) { TextView t = text(s, 12, MUTED, false); t.setPadding(0, dp(10), 0, 0); return t; }

    EditText edit(String v, int type) {
        EditText e = new EditText(this);
        e.setText(v); e.setInputType(type); e.setTextColor(TXT); e.setHintTextColor(MUTED);
        e.setSingleLine(true);
        return e;
    }

    CheckBox check(String s, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(s); c.setChecked(v); c.setTextColor(TXT);
        return c;
    }

    Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s); b.setTextColor(TXT); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(14), dp(12), dp(14));
        return b;
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD); g.setCornerRadius(dp(14));
        c.setBackground(g);
        parent.addView(c, lp());
        View spacer = new View(this);
        return c;
    }
}
