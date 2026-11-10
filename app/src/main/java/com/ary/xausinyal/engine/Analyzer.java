package com.ary.xausinyal.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Mesin analisa XAUUSD (pure Java, tanpa dependensi Android).
 * Metode: 1) Support/Resisten dari swing 15m, 2) konfirmasi arah dolar (DXY / EURUSD terbalik),
 * 3) order book (antrean order & posisi retail), plus Stochastic 5,3,3 di 5m.
 */
public class Analyzer {

    // ---------- Model data ----------
    public static class Candle {
        public final long t; // detik epoch UTC (waktu buka candle)
        public final double o, h, l, c;
        public Candle(long t, double o, double h, double l, double c) {
            this.t = t; this.o = o; this.h = h; this.l = l; this.c = c;
        }
    }

    public static class Level {
        public double price; public int touches; public long lastT;
        Level(double p, long t) { price = p; touches = 1; lastT = t; }
    }

    /** Ringkasan order book FastBull. Semua dalam persen. */
    public static class OrderBook {
        public double[] price;      // harga tiap baris
        public double[] ordBuy;     // open orders sisi buy
        public double[] ordSell;    // open orders sisi sell (nilai absolut)
        public double[] posBuy;     // open positions long
        public double[] posSell;    // open positions short
        public double[] posPrice;
        public double longPct = 50; // % posisi long dari total

        double sumOrders(boolean buy, double lo, double hi) {
            double s = 0;
            if (price == null) return 0;
            for (int i = 0; i < price.length; i++)
                if (price[i] >= lo && price[i] <= hi) s += buy ? ordBuy[i] : ordSell[i];
            return s;
        }
    }

    public static class Signal {
        public String side;   // BUY / SELL
        public String kind;   // PANTUL / JEBOL / SIAP
        public double entry, sl, tp1, tp2, rr;
        public int score;
        public List<String> reasons = new ArrayList<>();

        public String title() {
            if ("SIAP".equals(kind))
                return String.format(Locale.US, "SIAP-SIAP %s XAUUSD dekat %.1f", side, entry);
            return String.format(Locale.US, "%s XAUUSD @ %.1f (skor %d)", side, entry, score);
        }

        public String body() {
            StringBuilder b = new StringBuilder();
            if ("SIAP".equals(kind)) {
                b.append(String.format(Locale.US, "Rencana: %s di %.1f | SL %.1f | TP %.1f", side, entry, sl, tp1));
            } else {
                b.append(String.format(Locale.US, "Entry %.1f | SL %.1f | TP1 %.1f | TP2 %.1f | R:R 1:%.1f",
                        entry, sl, tp1, tp2, rr));
            }
            for (String r : reasons) b.append("\n• ").append(r);
            return b.toString();
        }
    }

    public static class Result {
        public double price, atr15, stochK, stochD;
        public int dollarDir; // +1 dolar menguat, -1 melemah, 0 netral/tidak ada data
        public int trend;     // +1 naik, -1 turun, 0 sideways (EMA 15m)
        public Level support, resistance, support2, resistance2;
        public double longPct = -1;
        public List<Signal> signals = new ArrayList<>();
        public String note = "";

        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "Harga: %.2f\n", price));
            b.append("Resisten: ").append(lv(resistance)).append("  (berikutnya ").append(lv(resistance2)).append(")\n");
            b.append("Support : ").append(lv(support)).append("  (berikutnya ").append(lv(support2)).append(")\n");
            b.append(String.format(Locale.US, "Stoch 5m: %.0f / %.0f   ATR15m: %.1f\n", stochK, stochD, atr15));
            b.append("Tren 15m: ").append(trend > 0 ? "NAIK" : trend < 0 ? "TURUN" : "SIDEWAYS").append("\n");
            b.append("Dolar: ").append(dollarDir > 0 ? "MENGUAT (dukung SELL emas)" :
                    dollarDir < 0 ? "MELEMAH (dukung BUY emas)" : "netral / tidak ada data").append("\n");
            if (longPct >= 0) b.append(String.format(Locale.US, "Posisi retail: %.0f%% long / %.0f%% short\n", longPct, 100 - longPct));
            if (!note.isEmpty()) b.append(note).append("\n");
            return b.toString();
        }

        private static String lv(Level l) {
            return l == null ? "-" : String.format(Locale.US, "%.1f (%dx)", l.price, l.touches);
        }
    }

    // ---------- Indikator ----------
    public static List<Candle> aggregate(List<Candle> src, int sec) {
        List<Candle> out = new ArrayList<>();
        Candle cur = null; long bucket = -1;
        double o = 0, h = 0, l = 0, c = 0;
        for (Candle k : src) {
            long b = (k.t / sec) * sec;
            if (b != bucket) {
                if (bucket >= 0) out.add(new Candle(bucket, o, h, l, c));
                bucket = b; o = k.o; h = k.h; l = k.l; c = k.c;
            } else {
                h = Math.max(h, k.h); l = Math.min(l, k.l); c = k.c;
            }
        }
        if (bucket >= 0) out.add(new Candle(bucket, o, h, l, c));
        return out;
    }

    public static double atr(List<Candle> k, int n) {
        if (k.size() < n + 1) return 5;
        double s = 0;
        for (int i = k.size() - n; i < k.size(); i++) {
            Candle a = k.get(i), p = k.get(i - 1);
            s += Math.max(a.h - a.l, Math.max(Math.abs(a.h - p.c), Math.abs(a.l - p.c)));
        }
        return s / n;
    }

    /** Stochastic (k, smoothK, d). Mengembalikan {K_prev, D_prev, K, D}. */
    public static double[] stoch(List<Candle> k, int len, int sm, int d) {
        int n = k.size();
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < len - 1) { raw[i] = Double.NaN; continue; }
            double hh = -1e18, ll = 1e18;
            for (int j = i - len + 1; j <= i; j++) { hh = Math.max(hh, k.get(j).h); ll = Math.min(ll, k.get(j).l); }
            raw[i] = hh - ll == 0 ? 50 : 100 * (k.get(i).c - ll) / (hh - ll);
        }
        double[] K = sma(raw, sm), D = sma(K, d);
        return new double[]{K[n - 2], D[n - 2], K[n - 1], D[n - 1]};
    }

    static double[] sma(double[] a, int n) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            if (i < n - 1) { r[i] = Double.NaN; continue; }
            double s = 0; boolean ok = true;
            for (int j = i - n + 1; j <= i; j++) { if (Double.isNaN(a[j])) { ok = false; break; } s += a[j]; }
            r[i] = ok ? s / n : Double.NaN;
        }
        return r;
    }

    public static double ema(List<Candle> k, int n, int endExclusiveOffset) {
        int end = k.size() - endExclusiveOffset;
        double a = 2.0 / (n + 1), e = k.get(Math.max(0, end - n * 3)).c;
        for (int i = Math.max(0, end - n * 3); i < end; i++) e = a * k.get(i).c + (1 - a) * e;
        return e;
    }

    /** Arah dolar dari data 15m. invert=true jika datanya EURUSD (EURUSD naik = dolar melemah). */
    public static int dollarDirection(List<Candle> d15, boolean invert) {
        if (d15 == null || d15.size() < 20) return 0;
        double now = ema(d15, 5, 0), before = ema(d15, 5, 3);
        double atrD = atr(d15, 14);
        double diff = now - before;
        int dir = 0;
        if (Math.abs(diff) > atrD * 0.25) dir = diff > 0 ? 1 : -1;
        return invert ? -dir : dir;
    }

    /** Swing high/low di 15m, dikelompokkan jadi level. */
    public static List<Level> levels(List<Candle> k15, double tol) {
        List<double[]> piv = new ArrayList<>(); // {price, time}
        int w = 3, from = Math.max(w, k15.size() - 170);
        for (int i = from; i < k15.size() - w; i++) {
            Candle c = k15.get(i);
            boolean hi = true, lo = true;
            for (int j = i - w; j <= i + w; j++) {
                if (j == i) continue;
                if (k15.get(j).h >= c.h) hi = false;
                if (k15.get(j).l <= c.l) lo = false;
            }
            if (hi) piv.add(new double[]{c.h, c.t});
            if (lo) piv.add(new double[]{c.l, c.t});
        }
        // ekstrem candle-candle terakhir (belum terkonfirmasi swing) tetap dihitung sebagai level
        List<Level> out = new ArrayList<>();
        Collections.sort(piv, (a, b) -> Double.compare(a[0], b[0]));
        for (double[] p : piv) {
            Level last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && Math.abs(p[0] - last.price) <= tol) {
                last.price = (last.price * last.touches + p[0]) / (last.touches + 1);
                last.touches++;
                last.lastT = Math.max(last.lastT, (long) p[1]);
            } else out.add(new Level(p[0], (long) p[1]));
        }
        return out;
    }

    // ---------- Analisa utama ----------
    /**
     * @param x5      candle XAUUSD 5m, urut lama -> baru, minimal ~250 candle. Candle terakhir boleh belum selesai.
     * @param d15     candle 15m DXY atau EURUSD (boleh null)
     * @param invertD true jika d15 adalah EURUSD
     * @param ob      order book (boleh null)
     */
    public static Result analyze(List<Candle> x5, List<Candle> d15, boolean invertD, OrderBook ob) {
        Result r = new Result();
        if (x5 == null || x5.size() < 60) { r.note = "Data harga belum cukup."; return r; }
        List<Candle> k15 = aggregate(x5, 900);
        Candle last = x5.get(x5.size() - 1); // candle 5m terakhir yang SUDAH close
        Candle prev = x5.get(x5.size() - 2);
        r.price = last.c;
        r.atr15 = atr(k15, 14);
        double tol = Math.max(3, r.atr15 * 0.35);
        double[] st = stoch(x5, 5, 3, 3);
        r.stochK = st[2]; r.stochD = st[3];
        r.dollarDir = dollarDirection(d15, invertD);
        double e21 = ema(k15, 21, 0), e55 = ema(k15, 55, 0);
        if (e21 > e55 && last.c > e55) r.trend = 1; else if (e21 < e55 && last.c < e55) r.trend = -1;
        if (ob != null) r.longPct = ob.longPct;

        List<Level> lv = levels(k15, tol);
        // tambahkan high/low 12 candle 15m terakhir sebagai batas range terkini
        double rh = -1e18, rl = 1e18; long rht = 0, rlt = 0;
        for (int i = Math.max(0, k15.size() - 13); i < k15.size() - 1; i++) {
            Candle c = k15.get(i);
            if (c.h > rh) { rh = c.h; rht = c.t; }
            if (c.l < rl) { rl = c.l; rlt = c.t; }
        }
        mergeLevel(lv, rh, rht, tol);
        mergeLevel(lv, rl, rlt, tol);

        double p = r.price;
        for (Level L : lv) {
            if (L.price < p - 0.5) {
                if (r.support == null || L.price > r.support.price) { r.support2 = r.support; r.support = L; }
                else if (r.support2 == null || L.price > r.support2.price) r.support2 = L;
            } else if (L.price > p + 0.5) {
                if (r.resistance == null || L.price < r.resistance.price) { r.resistance2 = r.resistance; r.resistance = L; }
                else if (r.resistance2 == null || L.price < r.resistance2.price) r.resistance2 = L;
            }
        }
        // perbaiki urutan level kedua
        r.support2 = nextBelow(lv, r.support);
        r.resistance2 = nextAbove(lv, r.resistance);
        if (r.support == null || r.resistance == null) { r.note = "Level support/resisten belum terbentuk."; return r; }

        double zone = Math.max(3, r.atr15 * 0.5);
        double slPad = Math.max(5, r.atr15 * 0.8);
        boolean crossUp = st[0] <= st[1] && st[2] > st[3] && st[0] < 30;
        boolean crossDn = st[0] >= st[1] && st[2] < st[3] && st[0] > 70;
        double body = Math.abs(last.c - last.o);
        boolean wickBull = (Math.min(last.o, last.c) - last.l) > Math.max(body, 0.5) * 1.5;
        boolean wickBear = (last.h - Math.max(last.o, last.c)) > Math.max(body, 0.5) * 1.5;

        // ===== A. PANTUL di support (BUY) =====
        if (r.support.touches >= 2 && (crossUp || wickBull) && last.c > r.support.price
                && p - r.support.price <= zone && last.l <= r.support.price + zone * 0.5) {
            double entry = p, sl = r.support.price - slPad;
            double tp1 = r.resistance.price - 1, tp2 = r.resistance2 != null ? r.resistance2.price - 1 : tp1 + (tp1 - entry);
            Signal s = build("BUY", "PANTUL", entry, sl, tp1, tp2);
            score(s, r, ob, true, r.support, crossUp || wickBull, "Stoch/candle menunjukkan pantulan naik");
            addIfValid(r, s);
        }
        // ===== B. PANTUL di resisten (SELL) =====
        if (r.resistance.touches >= 2 && (crossDn || wickBear) && last.c < r.resistance.price
                && r.resistance.price - p <= zone && last.h >= r.resistance.price - zone * 0.5) {
            double entry = p, sl = r.resistance.price + slPad;
            double tp1 = r.support.price + 1, tp2 = r.support2 != null ? r.support2.price + 1 : tp1 - (entry - tp1);
            Signal s = build("SELL", "PANTUL", entry, sl, tp1, tp2);
            score(s, r, ob, false, r.resistance, crossDn || wickBear, "Stoch/candle menunjukkan penolakan turun");
            addIfValid(r, s);
        }
        // ===== C. JEBOL support (SELL) & D. TEMBUS resisten (BUY) =====
        // memakai level yang baru saja ditembus oleh candle 5m yang sudah close
        Candle closed = last, before = prev;
        double buf = 1.5;
        for (Level L : lv) {
            if (closed.c < L.price - buf && before.c >= L.price - buf && L.touches >= 2) {
                double entry = p, sl = L.price + slPad * 0.8;
                Level nb = nextBelow(lv, L);
                double tp1 = nb != null && nb.price < entry - 3 ? nb.price + 1 : entry - (sl - entry) * 1.5;
                Level nb2 = nb != null ? nextBelow(lv, nb) : null;
                double tp2 = nb2 != null ? nb2.price + 1 : tp1 - (sl - entry);
                Signal s = build("SELL", "JEBOL", entry, sl, tp1, tp2);
                s.reasons.add(String.format(Locale.US, "Candle 5m close di bawah support %.1f (%dx teruji)", L.price, L.touches));
                score(s, r, ob, false, L, true, null);
                addIfValid(r, s);
            }
            if (closed.c > L.price + buf && before.c <= L.price + buf && L.touches >= 2) {
                double entry = p, sl = L.price - slPad * 0.8;
                Level na = nextAbove(lv, L);
                double tp1 = na != null && na.price > entry + 3 ? na.price - 1 : entry + (entry - sl) * 1.5;
                Level na2 = na != null ? nextAbove(lv, na) : null;
                double tp2 = na2 != null ? na2.price - 1 : tp1 + (entry - sl);
                Signal s = build("BUY", "JEBOL", entry, sl, tp1, tp2);
                s.reasons.add(String.format(Locale.US, "Candle 5m close di atas resisten %.1f (%dx teruji)", L.price, L.touches));
                score(s, r, ob, true, L, true, null);
                addIfValid(r, s);
            }
        }
        // sisakan satu sinyal terbaik per arah
        Signal bb = null, bs = null;
        for (Signal g : r.signals) {
            if ("BUY".equals(g.side)) { if (bb == null || g.score > bb.score) bb = g; }
            else if (bs == null || g.score > bs.score) bs = g;
        }
        r.signals.clear();
        if (bb != null) r.signals.add(bb);
        if (bs != null) r.signals.add(bs);
        // ===== E. SIAP-SIAP: harga mendekati level kuat =====
        if (r.signals.isEmpty()) {
            if (r.resistance.touches >= 3 && r.resistance.price - p <= zone * 1.5 && r.resistance.price - p > zone * 0.5) {
                Signal s = build("SELL", "SIAP", r.resistance.price, r.resistance.price + slPad, r.support.price + 1, r.support.price + 1);
                s.reasons.add(String.format(Locale.US, "Harga mendekati resisten %.1f (%dx teruji)", r.resistance.price, r.resistance.touches));
                s.reasons.add("Tunggu penolakan + Stoch turun dari atas 80");
                s.score = 0; r.signals.add(s);
            } else if (r.support.touches >= 3 && p - r.support.price <= zone * 1.5 && p - r.support.price > zone * 0.5) {
                Signal s = build("BUY", "SIAP", r.support.price, r.support.price - slPad, r.resistance.price - 1, r.resistance.price - 1);
                s.reasons.add(String.format(Locale.US, "Harga mendekati support %.1f (%dx teruji)", r.support.price, r.support.touches));
                s.reasons.add("Tunggu pantulan + Stoch naik dari bawah 20");
                s.score = 0; r.signals.add(s);
            }
        }
        return r;
    }

    static void mergeLevel(List<Level> lv, double price, long t, double tol) {
        for (Level L : lv) if (Math.abs(L.price - price) <= tol) { L.touches++; L.lastT = Math.max(L.lastT, t); return; }
        lv.add(new Level(price, t));
        Collections.sort(lv, (a, b) -> Double.compare(a.price, b.price));
    }

    static Level nextBelow(List<Level> lv, Level L) {
        if (L == null) return null; Level best = null;
        for (Level x : lv) if (x.price < L.price - 2 && (best == null || x.price > best.price)) best = x;
        return best;
    }

    static Level nextAbove(List<Level> lv, Level L) {
        if (L == null) return null; Level best = null;
        for (Level x : lv) if (x.price > L.price + 2 && (best == null || x.price < best.price)) best = x;
        return best;
    }

    static Signal build(String side, String kind, double e, double sl, double tp1, double tp2) {
        Signal s = new Signal();
        s.side = side; s.kind = kind;
        s.entry = round(e); s.sl = round(sl); s.tp1 = round(tp1); s.tp2 = round(tp2);
        double risk = Math.abs(e - sl), rew = Math.abs(tp1 - e);
        s.rr = risk == 0 ? 0 : Math.round(rew / risk * 10) / 10.0;
        return s;
    }

    static double round(double v) { return Math.round(v * 10) / 10.0; }

    static void score(Signal s, Result r, OrderBook ob, boolean buy, Level L, boolean trigger, String trigReason) {
        int sc = 45;
        if (trigger) { sc += 15; if (trigReason != null) s.reasons.add(trigReason); }
        else s.reasons.add("Belum ada konfirmasi Stoch/candle");
        if (L.touches >= 3) { sc += 10; s.reasons.add(String.format(Locale.US, "Level %.1f kuat (%dx teruji)", L.price, L.touches)); }
        else if (L.touches >= 2) sc += 5;
        int want = buy ? -1 : 1; // BUY emas butuh dolar melemah
        if (r.dollarDir == want) { sc += 15; s.reasons.add(buy ? "Dolar melemah → mendukung BUY" : "Dolar menguat → mendukung SELL"); }
        else if (r.dollarDir == -want) { sc -= 20; s.reasons.add(buy ? "⚠ Dolar menguat (melawan BUY)" : "⚠ Dolar melemah (melawan SELL)"); }
        else s.reasons.add("Dolar netral");
        if (ob != null && ob.price != null) {
            double z = 6;
            if ("PANTUL".equals(s.kind)) {
                double m = buy ? ob.sumOrders(true, L.price - z, L.price + z) : ob.sumOrders(false, L.price - z, L.price + z);
                if (m >= 1.5) { sc += 10; s.reasons.add(String.format(Locale.US, "Order book: antrean %s %.1f%% di sekitar level", buy ? "buy" : "sell", m)); }
            }
            if (ob.longPct >= 70 && !buy) { sc += 10; s.reasons.add(String.format(Locale.US, "Retail %.0f%% long → cenderung turun (kontrarian)", ob.longPct)); }
            else if (ob.longPct <= 30 && buy) { sc += 10; s.reasons.add(String.format(Locale.US, "Retail %.0f%% short → cenderung naik (kontrarian)", 100 - ob.longPct)); }
            else if (ob.longPct >= 70 && buy) { sc -= 5; }
            else if (ob.longPct <= 30 && !buy) { sc -= 5; }
        }
        int tw = buy ? 1 : -1;
        if (r.trend == tw) { sc += 10; s.reasons.add("Searah tren 15m"); }
        else if (r.trend == -tw) { sc -= 15; s.reasons.add("⚠ Melawan tren 15m"); }
        if (s.rr >= 2) sc += 5;
        s.score = Math.max(0, Math.min(100, sc));
    }

    static void addIfValid(Result r, Signal s) {
        if (s.rr < 1.2) return; // R:R terlalu kecil, lewati
        if ("BUY".equals(s.side) && !(s.sl < s.entry && s.tp1 > s.entry)) return;
        if ("SELL".equals(s.side) && !(s.sl > s.entry && s.tp1 < s.entry)) return;
        r.signals.add(s);
    }
}
