package com.ary.xausinyal.engine;

import com.ary.xausinyal.engine.Analyzer.Candle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Mesin analisa v2 — 80% teknikal (struktur pasar XAU), 20% fundamental.
 *
 * Teknikal (80):
 *   30  Struktur H1 + H4 (Higher High/Higher Low vs Lower High/Lower Low, break of structure)
 *   20  Kualitas level kunci H1 (swing teruji, high/low hari sebelumnya, angka bulat)
 *   20  Trigger entry M5 (pinbar / engulfing / Stochastic 5,3,3 menyilang)
 *   10  Manajemen risiko (R:R)
 * Fundamental (20):
 *   10  Kalender berita (sinyal diblokir ±30 menit dari berita besar)
 *   10  Arah dolar H1 (nilai tambah, bukan pembatal)
 */
public class StructureAnalyzer {

    public static class Level {
        public double price; public int touches; public boolean pdh, pdl, round;
        Level(double p) { price = p; touches = 1; }
        public int strength() { return touches + (pdh || pdl ? 1 : 0) + (round ? 1 : 0); }
    }

    public static class Signal {
        public String side, kind;
        public double entry, sl, tp1, tp2, rr;
        public int score, sStructure, sLevel, sTrigger, sRisk, sNews, sDollar;
        public List<String> reasons = new ArrayList<>();

        public String title() {
            if ("SIAP".equals(kind)) return String.format(Locale.US, "SIAP-SIAP %s XAUUSD dekat %.1f", side, entry);
            return String.format(Locale.US, "%s XAUUSD @ %.1f (skor %d)", side, entry, score);
        }

        public String body() {
            StringBuilder b = new StringBuilder();
            if ("SIAP".equals(kind))
                b.append(String.format(Locale.US, "Rencana: %s di %.1f | SL %.1f | TP %.1f", side, entry, sl, tp1));
            else
                b.append(String.format(Locale.US, "Entry %.1f | SL %.1f | TP1 %.1f | TP2 %.1f | R:R 1:%.1f", entry, sl, tp1, tp2, rr));
            if (!"SIAP".equals(kind))
                b.append(String.format(Locale.US, "\nTeknikal %d/80 (struktur %d, level %d, trigger %d, risiko %d) • Fundamental %d/20",
                        sStructure + sLevel + sTrigger + sRisk, sStructure, sLevel, sTrigger, sRisk, sNews + sDollar));
            for (String r : reasons) b.append("\n• ").append(r);
            return b.toString();
        }
    }

    public static class Result {
        public double price, atrH1, stochK, stochD;
        public int trendH1, trendH4, dollarDir;
        public boolean newsBlock;
        public String newsNote = "";
        public Level support, resistance;
        public double lastSwingHigh = Double.NaN, lastSwingLow = Double.NaN;
        public List<Signal> signals = new ArrayList<>();
        public String note = "";

        public static String trendName(int t) { return t > 0 ? "NAIK (HH/HL)" : t < 0 ? "TURUN (LH/LL)" : "SIDEWAYS"; }

        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "Harga: %.2f\n", price));
            b.append("Struktur H4: ").append(trendName(trendH4)).append("\n");
            b.append("Struktur H1: ").append(trendName(trendH1)).append("\n");
            if (!Double.isNaN(lastSwingHigh))
                b.append(String.format(Locale.US, "Swing H1: high %.1f / low %.1f\n", lastSwingHigh, lastSwingLow));
            b.append("Resisten: ").append(lv(resistance)).append("\n");
            b.append("Support : ").append(lv(support)).append("\n");
            b.append(String.format(Locale.US, "Stoch 5m: %.0f / %.0f   ATR H1: %.1f\n", stochK, stochD, atrH1));
            b.append("Dolar H1: ").append(dollarDir > 0 ? "menguat" : dollarDir < 0 ? "melemah" : "netral").append(" (bobot kecil)\n");
            if (newsBlock) b.append("⛔ ").append(newsNote).append("\n");
            if (!note.isEmpty()) b.append(note).append("\n");
            return b.toString();
        }

        static String lv(Level l) {
            if (l == null) return "-";
            String tag = l.pdh ? " PDH" : l.pdl ? " PDL" : "";
            return String.format(Locale.US, "%.1f (kekuatan %d%s)", l.price, l.strength(), tag);
        }
    }

    // ---------------- util ----------------
    static double atr(List<Candle> k, int n) {
        if (k.size() < n + 1) return 8;
        double s = 0;
        for (int i = k.size() - n; i < k.size(); i++) {
            Candle a = k.get(i), p = k.get(i - 1);
            s += Math.max(a.h - a.l, Math.max(Math.abs(a.h - p.c), Math.abs(a.l - p.c)));
        }
        return s / n;
    }

    /** Swing tinggi/rendah terkonfirmasi (w candle kiri & kanan). Mengembalikan {highs, lows} berisi {harga, index}. */
    static List<double[]>[] swings(List<Candle> k, int w, int lookback) {
        List<double[]> hs = new ArrayList<>(), ls = new ArrayList<>();
        int from = Math.max(w, k.size() - lookback);
        for (int i = from; i < k.size() - w; i++) {
            Candle c = k.get(i);
            boolean hi = true, lo = true;
            for (int j = i - w; j <= i + w; j++) {
                if (j == i) continue;
                if (k.get(j).h > c.h || (j > i && k.get(j).h == c.h)) hi = false;
                if (k.get(j).l < c.l || (j > i && k.get(j).l == c.l)) lo = false;
            }
            if (hi) hs.add(new double[]{c.h, i});
            if (lo) ls.add(new double[]{c.l, i});
        }
        @SuppressWarnings("unchecked") List<double[]>[] r = new List[]{hs, ls};
        return r;
    }

    /** Tren dari struktur swing + break of structure. */
    static int trend(List<Candle> k, int w, int lookback, double[] outLast) {
        if (k.size() < w * 2 + 6) return 0;
        List<double[]>[] sw = swings(k, w, lookback);
        List<double[]> hs = sw[0], ls = sw[1];
        if (hs.size() < 2 || ls.size() < 2) return 0;
        double h2 = hs.get(hs.size() - 1)[0], h1 = hs.get(hs.size() - 2)[0];
        double l2 = ls.get(ls.size() - 1)[0], l1 = ls.get(ls.size() - 2)[0];
        if (outLast != null) { outLast[0] = h2; outLast[1] = l2; }
        double close = k.get(k.size() - 1).c;
        // break of structure: close menembus swing terakhir mengalahkan pola lama
        if (close > h2) return 1;
        if (close < l2) return -1;
        if (h2 > h1 && l2 > l1) return 1;
        if (h2 < h1 && l2 < l1) return -1;
        return 0;
    }

    static double[] stoch(List<Candle> k) { return Analyzer.stoch(k, 5, 3, 3); }

    // ---------------- analisa ----------------
    /**
     * @param m5        candle XAUUSD 5m yang sudah close (lama -> baru), idealnya >= 1500 candle (±5 hari)
     * @param dollar15  candle 15m DXY atau EURUSD (boleh null)
     * @param invertD   true jika dollar15 adalah EURUSD
     * @param newsTimes waktu berita besar (epoch detik), boleh null
     */
    public static Result analyze(List<Candle> m5, List<Candle> dollar15, boolean invertD, long[] newsTimes) {
        Result r = new Result();
        if (m5 == null || m5.size() < 300) { r.note = "Data harga belum cukup (butuh ±1 hari data 5m)."; return r; }
        Candle last = m5.get(m5.size() - 1), prev = m5.get(m5.size() - 2);
        long now = last.t + 300;
        r.price = last.c;

        List<Candle> h1 = Analyzer.aggregate(m5, 3600);
        List<Candle> h4 = Analyzer.aggregate(m5, 14400);
        r.atrH1 = atr(h1, 14);
        double atr = r.atrH1;
        double[] lastSw = new double[]{Double.NaN, Double.NaN};
        r.trendH1 = trend(h1, 2, 72, lastSw);
        r.lastSwingHigh = lastSw[0]; r.lastSwingLow = lastSw[1];
        r.trendH4 = h4.size() >= 12 ? trend(h4, 2, 40, null) : 0;
        double[] st = stoch(m5);
        r.stochK = st[2]; r.stochD = st[3];

        // ---- fundamental: dolar H1 (dari data 15m)
        if (dollar15 != null && dollar15.size() >= 30) {
            List<Candle> dh = Analyzer.aggregate(dollar15, 3600);
            if (dh.size() >= 8) {
                double now1 = Analyzer.ema(dh, 4, 0), before = Analyzer.ema(dh, 4, 2);
                double a = atr(dh, 10);
                int dir = 0;
                if (Math.abs(now1 - before) > a * 0.2) dir = now1 > before ? 1 : -1;
                r.dollarDir = invertD ? -dir : dir;
            }
        }
        // ---- fundamental: kalender berita
        if (newsTimes != null) {
            for (long nt : newsTimes) {
                if (now >= nt - 1800 && now <= nt + 1800) {
                    r.newsBlock = true;
                    r.newsNote = "Jeda berita besar (±30 menit) — tidak ada sinyal entry";
                }
            }
        }

        // ---- level kunci
        double tol = Math.max(3, atr * 0.25);
        List<Level> levels = new ArrayList<>();
        List<double[]>[] sw = swings(h1, 2, 120);
        for (double[] s : sw[0]) addLevel(levels, s[0], tol);
        for (double[] s : sw[1]) addLevel(levels, s[0], tol);
        // high/low hari sebelumnya (hari UTC)
        long dayStart = (now / 86400) * 86400;
        double pdh = -1e9, pdl = 1e9;
        for (Candle c : h1) if (c.t >= dayStart - 86400 && c.t < dayStart) { pdh = Math.max(pdh, c.h); pdl = Math.min(pdl, c.l); }
        if (pdh > 0) { Level L = addLevel(levels, pdh, tol); L.pdh = true; }
        if (pdl < 1e8) { Level L = addLevel(levels, pdl, tol); L.pdl = true; }
        // angka bulat (kelipatan 50) dekat harga
        for (double rn = Math.floor((r.price - 150) / 50) * 50; rn <= r.price + 150; rn += 50) {
            for (Level L : levels) if (Math.abs(L.price - rn) <= tol) L.round = true;
        }
        Collections.sort(levels, (a, b) -> Double.compare(a.price, b.price));
        for (Level L : levels) {
            if (L.price < r.price && (r.support == null || L.price > r.support.price)) r.support = L;
            if (L.price > r.price && (r.resistance == null || L.price < r.resistance.price)) r.resistance = L;
        }
        if (r.newsBlock) return r;

        // ---- trigger M5
        double body = Math.abs(last.c - last.o), range = Math.max(0.01, last.h - last.l);
        double lowerWick = Math.min(last.o, last.c) - last.l, upperWick = last.h - Math.max(last.o, last.c);
        boolean pinBull = lowerWick >= Math.max(body, range * 0.15) * 1.2 && last.c > last.l + range * 0.55;
        boolean pinBear = upperWick >= Math.max(body, range * 0.15) * 1.2 && last.c < last.h - range * 0.55;
        boolean engBull = last.c > last.o && prev.c < prev.o && last.c >= prev.o && last.o <= prev.c;
        boolean engBear = last.c < last.o && prev.c > prev.o && last.c <= prev.o && last.o >= prev.c;
        boolean stUp = st[0] <= st[1] && st[2] > st[3] && Math.min(st[0], st[1]) < 30;
        boolean stDn = st[0] >= st[1] && st[2] < st[3] && Math.max(st[0], st[1]) > 70;

        double zone = Math.max(2.5, atr * 0.2);
        double recentLow = Math.min(last.l, Math.min(prev.l, m5.get(m5.size() - 3).l));
        double recentHigh = Math.max(last.h, Math.max(prev.h, m5.get(m5.size() - 3).h));

        for (Level L : levels) {
            // BUY: harga menyentuh zona level lalu close kembali di atasnya
            if (recentLow <= L.price + zone && recentLow >= L.price - zone * 1.6 && last.c > L.price && last.c - L.price <= atr * 0.6
                    && (pinBull || engBull || stUp)) {
                double sl = Math.min(L.price, recentLow) - Math.max(4, atr * 0.5);
                Level tpL = nextAbove(levels, last.c + Math.max(3, atr * 0.3), 2);
                if (tpL != null) {
                    Signal s = build("BUY", "PANTUL", last.c, sl, tpL.price - 1, nextAboveOr(levels, tpL, last.c, sl));
                    s.reasons.add(String.format(Locale.US, "Retest level %.1f (kekuatan %d)", L.price, L.strength()));
                    score(s, r, true, L, pinBull || engBull, stUp, pinBull ? "pinbar" : engBull ? "engulfing" : null);
                    add(r, s);
                }
            }
            // SELL: harga menyentuh zona level lalu close kembali di bawahnya
            if (recentHigh >= L.price - zone && recentHigh <= L.price + zone * 1.6 && last.c < L.price && L.price - last.c <= atr * 0.6
                    && (pinBear || engBear || stDn)) {
                double sl = Math.max(L.price, recentHigh) + Math.max(4, atr * 0.5);
                Level tpL = nextBelow(levels, last.c - Math.max(3, atr * 0.3), 2);
                if (tpL != null) {
                    Signal s = build("SELL", "PANTUL", last.c, sl, tpL.price + 1, nextBelowOr(levels, tpL, last.c, sl));
                    s.reasons.add(String.format(Locale.US, "Retest level %.1f (kekuatan %d)", L.price, L.strength()));
                    score(s, r, false, L, pinBear || engBear, stDn, pinBear ? "pinbar" : engBear ? "engulfing" : null);
                    add(r, s);
                }
            }
        }
        // satu sinyal terbaik per arah
        Signal bb = null, bs = null;
        for (Signal g : r.signals) {
            if ("BUY".equals(g.side)) { if (bb == null || g.score > bb.score) bb = g; }
            else if (bs == null || g.score > bs.score) bs = g;
        }
        r.signals.clear();
        if (bb != null) r.signals.add(bb);
        if (bs != null) r.signals.add(bs);

        // SIAP-SIAP: harga mendekati level kuat searah struktur
        if (r.signals.isEmpty()) {
            if (r.trendH1 >= 0 && r.support != null && r.support.strength() >= 3 && r.price - r.support.price <= atr * 0.6 && r.price - r.support.price > zone) {
                Signal s = build("BUY", "SIAP", r.support.price, r.support.price - Math.max(4, atr * 0.5),
                        r.resistance != null ? r.resistance.price - 1 : r.support.price + atr, 0);
                s.reasons.add("Struktur H1 " + Result.trendName(r.trendH1) + ", harga mendekati support kuat");
                s.reasons.add("Tunggu candle 5m pantul (pinbar/engulfing) di level");
                r.signals.add(s);
            } else if (r.trendH1 <= 0 && r.resistance != null && r.resistance.strength() >= 3 && r.resistance.price - r.price <= atr * 0.6 && r.resistance.price - r.price > zone) {
                Signal s = build("SELL", "SIAP", r.resistance.price, r.resistance.price + Math.max(4, atr * 0.5),
                        r.support != null ? r.support.price + 1 : r.resistance.price - atr, 0);
                s.reasons.add("Struktur H1 " + Result.trendName(r.trendH1) + ", harga mendekati resisten kuat");
                s.reasons.add("Tunggu candle 5m ditolak (pinbar/engulfing) di level");
                r.signals.add(s);
            }
        }
        return r;
    }

    static Level addLevel(List<Level> lv, double p, double tol) {
        for (Level L : lv) if (Math.abs(L.price - p) <= tol) {
            L.price = (L.price * L.touches + p) / (L.touches + 1);
            L.touches++;
            return L;
        }
        Level n = new Level(p);
        lv.add(n);
        return n;
    }

    static Level nextAbove(List<Level> lv, double from, int minStrength) {
        Level best = null;
        for (Level L : lv) if (L.price > from && L.strength() >= minStrength && (best == null || L.price < best.price)) best = L;
        return best;
    }

    static Level nextBelow(List<Level> lv, double from, int minStrength) {
        Level best = null;
        for (Level L : lv) if (L.price < from && L.strength() >= minStrength && (best == null || L.price > best.price)) best = L;
        return best;
    }

    static double nextAboveOr(List<Level> lv, Level tp, double entry, double sl) {
        Level n = nextAbove(lv, tp.price + 2, 1);
        return n != null ? n.price - 1 : tp.price + (entry - sl);
    }

    static double nextBelowOr(List<Level> lv, Level tp, double entry, double sl) {
        Level n = nextBelow(lv, tp.price - 2, 1);
        return n != null ? n.price + 1 : tp.price - (sl - entry);
    }

    static Signal build(String side, String kind, double e, double sl, double tp1, double tp2) {
        Signal s = new Signal();
        s.side = side; s.kind = kind;
        s.entry = r1(e); s.sl = r1(sl); s.tp1 = r1(tp1); s.tp2 = r1(tp2);
        double risk = Math.abs(e - sl), rew = Math.abs(tp1 - e);
        s.rr = risk == 0 ? 0 : Math.round(rew / risk * 10) / 10.0;
        return s;
    }

    static double r1(double v) { return Math.round(v * 10) / 10.0; }

    static void score(Signal s, Result r, boolean buy, Level L, boolean candle, boolean stochX, String candleName) {
        int dir = buy ? 1 : -1;
        // Struktur 30
        int st = 0;
        if (r.trendH1 == dir) { st += 20; s.reasons.add("Searah struktur H1 " + Result.trendName(r.trendH1)); }
        else if (r.trendH1 == 0) { st += 8; s.reasons.add("Struktur H1 sideways (entry di ujung range)"); }
        else s.reasons.add("⚠ Melawan struktur H1 " + Result.trendName(r.trendH1));
        if (r.trendH4 == dir) { st += 10; s.reasons.add("Searah struktur H4"); }
        else if (r.trendH4 == 0) st += 4;
        s.sStructure = st;
        // Level 20
        int k = L.strength();
        s.sLevel = k >= 4 ? 20 : k == 3 ? 15 : k == 2 ? 10 : 4;
        if (L.pdh || L.pdl) s.reasons.add(L.pdh ? "Level = high hari sebelumnya" : "Level = low hari sebelumnya");
        if (L.round) s.reasons.add("Level dekat angka bulat");
        // Trigger 20
        int tg = 0;
        if (candle) { tg += 12; s.reasons.add("Candle 5m " + candleName + " di level"); }
        if (stochX) { tg += 8; s.reasons.add(buy ? "Stoch 5m menyilang naik dari bawah 30" : "Stoch 5m menyilang turun dari atas 70"); }
        s.sTrigger = tg;
        // Risiko 10
        s.sRisk = s.rr >= 2 ? 10 : s.rr >= 1.5 ? 6 : 0;
        // Fundamental 20
        s.sNews = r.newsBlock ? 0 : 10;
        int want = buy ? -1 : 1;
        s.sDollar = r.dollarDir == want ? 10 : r.dollarDir == 0 ? 5 : 0;
        if (r.dollarDir == want) s.reasons.add(buy ? "Dolar H1 melemah (nilai tambah)" : "Dolar H1 menguat (nilai tambah)");
        s.score = s.sStructure + s.sLevel + s.sTrigger + s.sRisk + s.sNews + s.sDollar;
    }

    static void add(Result r, Signal s) {
        if (s.rr < 1.5) return; // tidak ada entry di tengah range / R:R jelek
        if ("BUY".equals(s.side) && !(s.sl < s.entry && s.tp1 > s.entry)) return;
        if ("SELL".equals(s.side) && !(s.sl > s.entry && s.tp1 < s.entry)) return;
        r.signals.add(s);
    }
}
