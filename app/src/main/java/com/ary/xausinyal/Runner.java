package com.ary.xausinyal;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.ary.xausinyal.engine.Analyzer;
import com.ary.xausinyal.engine.StructureAnalyzer;

import java.util.ArrayList;
import java.util.Calendar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Satu siklus: ambil data -> analisa -> notifikasi. Dipakai service dan tombol "Analisa sekarang". */
public class Runner {
    public static final String PREFS = "xausinyal";
    public static final String CH_SIGNAL = "sinyal_entry";
    public static final String CH_SERVICE = "layanan";

    // cache data antar siklus (hemat kuota API)
    static List<Analyzer.Candle> cacheDollar;
    static long dollarFetchedAt;
    static Analyzer.OrderBook cacheOb;
    static long obFetchedAt;
    public static volatile StructureAnalyzer.Result lastResult;
    public static volatile long lastResultAt;
    public static volatile String lastError = "";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel s = new NotificationChannel(CH_SIGNAL, "Sinyal entry XAUUSD", NotificationManager.IMPORTANCE_HIGH);
            s.setDescription("Notifikasi saat ada peluang entry BUY/SELL");
            s.enableVibration(true);
            nm.createNotificationChannel(s);
            NotificationChannel b = new NotificationChannel(CH_SERVICE, "Status pemantauan", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(b);
        }
    }

    static String wib(long epochSec) {
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
        return f.format(new Date(epochSec * 1000));
    }

    /** @return ringkasan teks untuk ditampilkan */
    public static synchronized String runOnce(Context ctx, boolean notify, boolean forceAll) {
        SharedPreferences p = prefs(ctx);
        String key = p.getString("apikey", "").trim();
        if (key.isEmpty()) return "Isi dulu API key Twelve Data.";
        String dSym = p.getString("dollar", "EUR/USD").trim();
        boolean invert = dSym.toUpperCase(Locale.US).contains("EUR") || dSym.toUpperCase(Locale.US).contains("GBP");
        double offset = parse(p.getString("offset", "0"), 0);
        int minScore = (int) parse(p.getString("minscore", "70"), 70);
        boolean useOb = p.getBoolean("useob2", false);
        boolean siap = p.getBoolean("siap", true);
        long now = System.currentTimeMillis() / 1000;
        StringBuilder err = new StringBuilder();
        int calls = 1;

        List<Analyzer.Candle> x5;
        try {
            x5 = DataClient.candles(key, "XAU/USD", "5min", 2000, 300, offset);
        } catch (Exception e) {
            String m = "Gagal ambil harga emas: " + e.getMessage();
            p.edit().putString("last_summary", wib(now) + " WIB\n" + m).apply();
            return m;
        }
        if (forceAll || cacheDollar == null || now - dollarFetchedAt >= 840) {
            try {
                calls++;
                cacheDollar = DataClient.candles(key, dSym, "15min", 200, 900, 0);
                dollarFetchedAt = now;
            } catch (Exception e) { err.append("Dolar: ").append(e.getMessage()).append("\n"); }
        }
        if (useOb && (forceAll || cacheOb == null || now - obFetchedAt >= 1500)) {
            try {
                cacheOb = DataClient.orderBook(offset);
                obFetchedAt = now;
            } catch (Exception e) { err.append("Order book: ").append(e.getMessage()).append("\n"); }
        }

        StructureAnalyzer.Result r = StructureAnalyzer.analyze(x5, cacheDollar, invert, newsTimes(p, now));
        lastResult = r;
        lastResultAt = now;
        lastError = err.toString();
        StringBuilder out = new StringBuilder();
        out.append("Update: ").append(wib(now)).append(" WIB\n").append(r.summary());
        if (useOb && cacheOb != null)
            out.append(String.format(Locale.US, "Order book (info saja): retail %.0f%% long\n", cacheOb.longPct));
        if (err.length() > 0) out.append("⚠ ").append(err);

        boolean any = false;
        for (StructureAnalyzer.Signal s : r.signals) {
            boolean isSiap = "SIAP".equals(s.kind);
            if (isSiap && !siap) continue;
            if (!isSiap && s.score < minScore) {
                out.append(String.format(Locale.US, "\n(Calon %s skor %d, di bawah batas %d)", s.side, s.score, minScore));
                continue;
            }
            any = true;
            out.append("\n\n▶ ").append(s.title()).append("\n").append(s.body());
            if (notify) maybeNotify(ctx, p, s, now);
        }
        if (!any) out.append(r.newsBlock ? "\n\n⛔ Jeda berita: jangan entry dulu." : "\n\nBelum ada setup entry. Tunggu harga menguji level kunci searah struktur.");
        countCall(p, calls);
        out.append("\n\nKuota Twelve Data: ").append(p.getInt("calls_today", 0)).append(" / 800 panggilan hari ini");
        String summary = out.toString();
        p.edit().putString("last_summary", summary).apply();
        return summary;
    }

    static void countCall(SharedPreferences p, int n) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        int c = day.equals(p.getString("calls_day", "")) ? p.getInt("calls_today", 0) : 0;
        p.edit().putString("calls_day", day).putInt("calls_today", c + n).apply();
    }

    static void maybeNotify(Context ctx, SharedPreferences p, StructureAnalyzer.Signal s, long now) {
        String ck = "cool_" + s.side + "_" + s.kind;
        long cool = "SIAP".equals(s.kind) ? 3600 : 2700;
        if (now - p.getLong(ck, 0) < cool) return;
        p.edit().putLong(ck, now).apply();

        String line = wib(now) + " | " + s.title() + "\n" + s.body().split("\n")[0];
        String hist = p.getString("history", "");
        String[] lines = (line + "\n\n" + hist).split("\n\n");
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < Math.min(30, lines.length); i++) { if (i > 0) h.append("\n\n"); h.append(lines[i]); }
        p.edit().putString("history", h.toString()).apply();

        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(ctx, CH_SIGNAL)
                : new android.app.Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(("BUY".equals(s.side) ? "🟢 " : "🔴 ") + s.title())
                .setContentText(s.body().split("\n")[0])
                .setStyle(new android.app.Notification.BigTextStyle().bigText(s.body()))
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(android.app.Notification.PRIORITY_HIGH)
                .setDefaults(android.app.Notification.DEFAULT_ALL);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify((int) (now % 100000), b.build()); } catch (SecurityException ignored) { }
    }

    /**
     * Jadwal berita besar: NFP otomatis (Jumat pertama tiap bulan, 08:30 waktu New York)
     * + jam berita yang diisi pengguna untuk hari ini (WIB), mis. "20:30, 02:00".
     */
    public static long[] newsTimes(SharedPreferences p, long now) {
        List<Long> out = new ArrayList<>();
        Calendar ny = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
        ny.setTimeInMillis(now * 1000);
        for (int m = -1; m <= 0; m++) {
            Calendar c = (Calendar) ny.clone();
            c.add(Calendar.MONTH, m);
            c.set(Calendar.DAY_OF_MONTH, 1);
            while (c.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) c.add(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 8); c.set(Calendar.MINUTE, 30); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            out.add(c.getTimeInMillis() / 1000);
        }
        String s = p.getString("news", "").trim();
        if (!s.isEmpty()) {
            Calendar j = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
            for (String part : s.split("[,;\\s]+")) {
                try {
                    String[] hm = part.trim().replace('.', ':').split(":");
                    Calendar c = (Calendar) j.clone();
                    c.setTimeInMillis(now * 1000);
                    c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                    c.set(Calendar.MINUTE, hm.length > 1 ? Integer.parseInt(hm[1]) : 0);
                    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                    out.add(c.getTimeInMillis() / 1000);
                } catch (Exception ignored) { }
            }
        }
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    static double parse(String s, double d) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (Exception e) { return d; }
    }
}
