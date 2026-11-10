package com.ary.xausinyal;

import android.content.Context;
import android.content.SharedPreferences;

import com.ary.xausinyal.engine.StructureAnalyzer;

import java.util.Locale;

/** Jawaban tombol "Cek sekarang" — ringkas, berbasis struktur pasar (80% teknikal / 20% fundamental). */
public class ChatBot {

    public static String reply(Context ctx, String msg) {
        long now = System.currentTimeMillis() / 1000;
        if (Runner.lastResult == null || now - Runner.lastResultAt > 60) {
            String s = Runner.runOnce(ctx, false, false);
            if (Runner.lastResult == null) return "Gagal membaca pasar: " + s;
        }
        return check(Runner.lastResult, Runner.prefs(ctx));
    }

    static String f(double v) { return String.format(Locale.US, "%.1f", v); }

    static String check(StructureAnalyzer.Result r, SharedPreferences p) {
        int minScore = (int) Runner.parse(p.getString("minscore", "70"), 70);
        StringBuilder b = new StringBuilder();
        b.append("Kondisi ").append(Runner.wib(Runner.lastResultAt)).append(" WIB:\n");
        b.append("• Emas ").append(String.format(Locale.US, "%.2f", r.price)).append("\n");
        b.append("• Struktur H4: ").append(StructureAnalyzer.Result.trendName(r.trendH4)).append("\n");
        b.append("• Struktur H1: ").append(StructureAnalyzer.Result.trendName(r.trendH1)).append("\n");
        if (r.resistance != null) b.append("• Resisten ").append(f(r.resistance.price));
        if (r.support != null) b.append(" | Support ").append(f(r.support.price));
        b.append("\n");
        b.append(String.format(Locale.US, "• Stoch 5m %.0f%s\n", r.stochK,
                r.stochK < 20 ? " (jenuh jual)" : r.stochK > 80 ? " (jenuh beli)" : ""));
        b.append("• Dolar H1 ").append(r.dollarDir > 0 ? "menguat" : r.dollarDir < 0 ? "melemah" : "netral").append(" (bobot 10%)\n\n");

        if (r.newsBlock) {
            b.append("⛔ JEDA BERITA BESAR. Jangan buka posisi sampai 30 menit setelah rilis, lalu tunggu candle 15m pertama selesai.");
            return b.toString();
        }

        StructureAnalyzer.Signal best = null, siap = null;
        for (StructureAnalyzer.Signal s : r.signals) {
            if ("SIAP".equals(s.kind)) siap = s;
            else if (s.score >= minScore && (best == null || s.score > best.score)) best = s;
        }
        if (best != null) {
            b.append("✅ BISA ").append(best.side).append(" sekarang\n")
                    .append("Entry ").append(f(best.entry)).append(" | SL ").append(f(best.sl))
                    .append(" | TP1 ").append(f(best.tp1)).append(" | TP2 ").append(f(best.tp2))
                    .append("\nSkor ").append(best.score).append("/100, R:R 1:").append(best.rr)
                    .append(String.format(Locale.US, "\nTeknikal %d/80 • Fundamental %d/20",
                            best.sStructure + best.sLevel + best.sTrigger + best.sRisk, best.sNews + best.sDollar));
            int k = 0;
            for (String why : best.reasons) { if (k++ >= 5) break; b.append("\n• ").append(why); }
            b.append(String.format(Locale.US, "\n\nRisiko 0,01 lot kalau kena SL: ±$%.0f", Math.abs(best.entry - best.sl)));
        } else if (siap != null) {
            b.append("⏳ SIAP-SIAP ").append(siap.side).append(" di sekitar ").append(f(siap.entry)).append("\n")
                    .append("SL ").append(f(siap.sl)).append(" | TP ").append(f(siap.tp1)).append("\n");
            for (String why : siap.reasons) b.append("• ").append(why).append("\n");
        } else {
            b.append("⏸ JANGAN ENTRY DULU.\n");
            String arah = r.trendH1 > 0 ? "BUY" : r.trendH1 < 0 ? "SELL" : null;
            if (arah == null) {
                b.append("Struktur H1 sideways: hanya entry di ujung range dengan candle konfirmasi.");
                if (r.support != null && r.resistance != null)
                    b.append("\n• BUY dekat ").append(f(r.support.price)).append(" • SELL dekat ").append(f(r.resistance.price));
            } else if (arah.equals("BUY") && r.support != null) {
                b.append("Struktur H1 naik → cari BUY saat harga turun ke support ").append(f(r.support.price))
                        .append(" dan muncul candle 5m pantul. Jangan SELL melawan struktur.");
            } else if (arah.equals("SELL") && r.resistance != null) {
                b.append("Struktur H1 turun → cari SELL saat harga naik ke resisten ").append(f(r.resistance.price))
                        .append(" dan muncul candle 5m ditolak. Jangan BUY melawan struktur.");
            }
        }
        b.append("\n\nSelalu pasang SL dan gunakan lot kecil.");
        return b.toString();
    }
}
