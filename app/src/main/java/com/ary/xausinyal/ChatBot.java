package com.ary.xausinyal;

import android.content.Context;
import android.content.SharedPreferences;

import com.ary.xausinyal.engine.Analyzer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kolom chat: perintah "cek sekarang", "posisi buy 3898 x7", "bantuan".
 * Pertanyaan bebas dijawab Claude jika API key Claude diisi (opsional).
 */
public class ChatBot {

    static final Pattern POS = Pattern.compile(
            "(buy|beli|sell|jual)\\D{0,15}?(\\d{4}(?:[.,]\\d{1,2})?)(?:\\D{0,12}?(?:x|×|sebanyak|posisi|pos)\\s*(\\d{1,2}))?",
            Pattern.CASE_INSENSITIVE);
    static final Pattern COUNT_BEFORE = Pattern.compile("(\\d{1,2})\\s*(?:posisi|pos)", Pattern.CASE_INSENSITIVE);

    public static String reply(Context ctx, String msg) {
        String m = msg.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) return "";
        if (m.equals("bantuan") || m.equals("help") || m.equals("?")) return help(ctx);

        Matcher pm = POS.matcher(m);
        boolean wantsPos = pm.find() && !m.startsWith("cek posisi?");
        boolean wantsCheck = m.contains("cek") || m.contains("analisa") || m.contains("analisis")
                || m.contains("gimana") || m.contains("bagaimana") || m.contains("bisa") || wantsPos;

        boolean ruleBased = wantsPos || m.startsWith("cek") || m.startsWith("analisa") || m.equals("update");
        String claudeKey = Runner.prefs(ctx).getString("claudekey", "").trim();

        // perbarui data bila perlu (maks 1x per 60 detik dari chat)
        if (wantsCheck || ruleBased) {
            long now = System.currentTimeMillis() / 1000;
            if (Runner.lastResult == null || now - Runner.lastResultAt > 60) {
                String s = Runner.runOnce(ctx, false, false);
                if (Runner.lastResult == null) return "Gagal membaca pasar: " + s;
            }
        }

        if (wantsPos) {
            String side = pm.group(1).startsWith("b") ? "BUY" : "SELL";
            double entry = Double.parseDouble(pm.group(2).replace(',', '.'));
            int n = 1;
            if (pm.group(3) != null) n = Integer.parseInt(pm.group(3));
            else { Matcher cb = COUNT_BEFORE.matcher(m); if (cb.find()) n = Integer.parseInt(cb.group(1)); }
            return position(Runner.lastResult, side, entry, Math.max(1, n));
        }
        if (ruleBased || claudeKey.isEmpty()) {
            if (!ruleBased && claudeKey.isEmpty() && !wantsCheck)
                return "Saya belum paham perintah itu.\n\n" + help(ctx);
            return check(Runner.lastResult, Runner.prefs(ctx));
        }
        return askClaude(ctx, msg, claudeKey);
    }

    static String help(Context ctx) {
        String s = "Perintah yang bisa dipakai:\n"
                + "• cek sekarang → kondisi pasar + saran entry\n"
                + "• posisi buy 3898 x7 → cek posisi Bapak (ganti buy/sell, harga, jumlah posisi)\n"
                + "• sell 3911 → cek posisi sell di 3911\n"
                + "• bantuan → daftar perintah ini";
        if (Runner.prefs(ctx).getString("claudekey", "").trim().isEmpty())
            s += "\n\nIngin tanya bebas seperti chat dengan Claude? Isi API key Claude di Pengaturan (berbayar, opsional).";
        else s += "\n\nPertanyaan lain akan dijawab Claude memakai data analisa terbaru.";
        return s;
    }

    static String f(double v) { return String.format(Locale.US, "%.1f", v); }

    static String dollarTxt(int d) {
        return d > 0 ? "dolar menguat (dukung SELL)" : d < 0 ? "dolar melemah (dukung BUY)" : "dolar netral";
    }

    /** Jawaban "cek sekarang" versi singkat dan langsung. */
    static String check(Analyzer.Result r, SharedPreferences p) {
        int minScore = (int) Runner.parse(p.getString("minscore", "70"), 70);
        StringBuilder b = new StringBuilder();
        b.append("Kondisi ").append(Runner.wib(Runner.lastResultAt)).append(" WIB:\n");
        b.append("• Emas ").append(String.format(Locale.US, "%.2f", r.price))
                .append(", tren 15m ").append(r.trend > 0 ? "NAIK" : r.trend < 0 ? "TURUN" : "SIDEWAYS").append("\n");
        b.append("• ").append(dollarTxt(r.dollarDir)).append("\n");
        b.append(String.format(Locale.US, "• Stoch 5m %.0f%s\n", r.stochK,
                r.stochK < 20 ? " (jenuh jual)" : r.stochK > 80 ? " (jenuh beli)" : ""));
        if (r.longPct >= 0) b.append(String.format(Locale.US, "• Retail %.0f%% long\n", r.longPct));
        if (r.support == null || r.resistance == null) return b.append("\nLevel belum terbentuk, tunggu data.").toString();
        b.append("• Resisten ").append(f(r.resistance.price)).append(" | Support ").append(f(r.support.price)).append("\n\n");

        Analyzer.Signal best = null, siap = null;
        for (Analyzer.Signal s : r.signals) {
            if ("SIAP".equals(s.kind)) siap = s;
            else if (s.score >= minScore && (best == null || s.score > best.score)) best = s;
        }
        double pad = Math.max(5, r.atr15 * 0.8);
        if (best != null) {
            b.append("✅ BISA ").append(best.side).append(" sekarang\n")
                    .append("Entry ").append(f(best.entry)).append(" | SL ").append(f(best.sl))
                    .append(" | TP1 ").append(f(best.tp1)).append(" | TP2 ").append(f(best.tp2))
                    .append("\nSkor ").append(best.score).append(", R:R 1:").append(best.rr);
            int k = 0;
            for (String why : best.reasons) { if (k++ >= 4) break; b.append("\n• ").append(why); }
        } else if (siap != null) {
            b.append("⏳ SIAP-SIAP ").append(siap.side).append(" di sekitar ").append(f(siap.entry)).append("\n")
                    .append("SL ").append(f(siap.sl)).append(" | TP ").append(f(siap.tp1)).append("\n")
                    .append("BUY".equals(siap.side) ? "Masuk hanya kalau ada candle 5m pantul naik + Stoch naik dari bawah 20."
                            : "Masuk hanya kalau ada candle 5m ditolak turun + Stoch turun dari atas 80.");
        } else {
            b.append("⏸ JANGAN ENTRY DULU, harga di tengah.\nRencana:\n")
                    .append("• BUY dekat ").append(f(r.support.price)).append(" kalau pantul → SL ")
                    .append(f(r.support.price - pad)).append(", TP ").append(f(r.resistance.price - 1)).append("\n")
                    .append("• SELL dekat ").append(f(r.resistance.price)).append(" kalau ditolak → SL ")
                    .append(f(r.resistance.price + pad)).append(", TP ").append(f(r.support.price + 1));
            int bias = -r.dollarDir + r.trend;
            if (r.longPct >= 70) bias--; else if (r.longPct >= 0 && r.longPct <= 30) bias++;
            if (bias >= 2) b.append("\nCondong ke BUY.");
            else if (bias <= -2) b.append("\nCondong ke SELL.");
        }
        b.append("\n\nPakai lot kecil dan selalu pasang SL.");
        return b.toString();
    }

    /** Evaluasi posisi yang sedang dibuka. */
    static String position(Analyzer.Result r, String side, double entry, int n) {
        boolean buy = "BUY".equals(side);
        double p = r.price;
        double move = buy ? p - entry : entry - p;
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "Posisi %s %s (%d posisi @0,01)\n", side, f(entry), n));
        b.append(String.format(Locale.US, "Harga sekarang %.2f → %s$%.1f per posisi (total ±%.1f USC/USD per 0,01 lot)\n\n",
                p, move >= 0 ? "+" : "", move, move * n));
        double pad = Math.max(5, r.atr15 * 0.8);
        Analyzer.Level slLevel = buy ? (r.support != null && r.support.price < p ? r.support : r.support2)
                : (r.resistance != null && r.resistance.price > p ? r.resistance : r.resistance2);
        Analyzer.Level tpLevel = buy ? r.resistance : r.support;
        double sl = slLevel != null ? (buy ? slLevel.price - pad : slLevel.price + pad) : (buy ? p - pad * 2 : p + pad * 2);
        double tp = tpLevel != null ? (buy ? tpLevel.price - 1 : tpLevel.price + 1) : (buy ? entry + pad * 2 : entry - pad * 2);
        if (buy && tp <= p) tp = r.resistance2 != null ? r.resistance2.price - 1 : p + pad;
        if (!buy && tp >= p) tp = r.support2 != null ? r.support2.price + 1 : p - pad;

        int pro = 0, con = 0;
        StringBuilder why = new StringBuilder();
        int wantD = buy ? -1 : 1, wantT = buy ? 1 : -1;
        if (r.dollarDir == wantD) { pro++; why.append("• ").append(dollarTxt(r.dollarDir)).append(" ✔\n"); }
        else if (r.dollarDir == -wantD) { con++; why.append("• ").append(dollarTxt(r.dollarDir)).append(" ✘\n"); }
        if (r.trend == wantT) { pro++; why.append("• searah tren 15m ✔\n"); }
        else if (r.trend == -wantT) { con++; why.append("• melawan tren 15m ✘\n"); }
        if (r.longPct >= 70) { if (buy) { con++; why.append(String.format(Locale.US, "• retail %.0f%% long (rawan turun) ✘\n", r.longPct)); } else { pro++; why.append(String.format(Locale.US, "• retail %.0f%% long (kontrarian) ✔\n", r.longPct)); } }
        else if (r.longPct >= 0 && r.longPct <= 30) { if (buy) { pro++; why.append("• retail mayoritas short ✔\n"); } else { con++; why.append("• retail mayoritas short ✘\n"); } }
        if (buy ? r.stochK < 20 : r.stochK > 80) { pro++; why.append("• Stoch 5m jenuh, peluang pantul ✔\n"); }

        b.append(why);
        b.append("\nSaran:\n");
        b.append("• SL: ").append(f(sl)).append(slLevel != null ? " (di balik level " + f(slLevel.price) + ")" : "").append("\n");
        b.append("• TP: ").append(f(tp)).append("\n");
        double risk = Math.abs(entry - sl) * n;
        b.append(String.format(Locale.US, "• Risiko kalau kena SL: ±%.0f (USC/USD per 0,01 lot)\n", risk));

        if (con >= 2 && pro == 0) {
            b.append(move >= -1 ? "⚠ Kondisi melawan posisi Bapak. Pertimbangkan TUTUP di sekitar impas."
                    : "⚠ Kondisi melawan posisi Bapak. Pasang SL di atas, jangan tambah posisi"
                    + (n > 2 ? ", dan pertimbangkan tutup sebagian." : "."));
        } else if (pro >= 2 && con == 0) {
            b.append("👍 Kondisi mendukung. Tahan, dan geser SL ke ").append(f(entry))
                    .append(" (impas) kalau sudah ").append(buy ? "+" : "+").append("$5.");
        } else {
            b.append("➖ Kondisi campuran. Tahan dengan SL ketat; kalau sudah untung, segera kunci di impas.");
        }
        return b.toString();
    }

    /** Pertanyaan bebas ke Claude (opsional). */
    static String askClaude(Context ctx, String msg, String key) {
        try {
            if (Runner.lastResult == null) Runner.runOnce(ctx, false, false);
            String data = Runner.lastResult != null ? Runner.lastResult.summary() : "(data pasar belum tersedia)";
            SharedPreferences p = Runner.prefs(ctx);
            String system = "Kamu asisten trading XAUUSD untuk Pak Ari, berbahasa Indonesia, gaya singkat dan langsung (maks 8 baris). "
                    + "Metode: 1) support/resisten, 2) korelasi dolar (DXY), 3) order book. Entry hanya di ujung level dengan konfirmasi; "
                    + "selalu sebut SL dan TP; tolak membuat keputusan pasti, sebut ini bukan saran keuangan bila relevan. "
                    + "Data analisa terbaru (" + Runner.wib(Runner.lastResultAt) + " WIB):\n" + data;
            JSONArray msgs = new JSONArray();
            JSONArray hist = new JSONArray(p.getString("chat", "[]"));
            int from = Math.max(0, hist.length() - 10);
            for (int i = from; i < hist.length(); i++) {
                JSONObject h = hist.getJSONObject(i);
                String role = h.getBoolean("me") ? "user" : "assistant";
                if (msgs.length() == 0 && !role.equals("user")) continue;
                if (msgs.length() > 0 && msgs.getJSONObject(msgs.length() - 1).getString("role").equals(role)) continue;
                msgs.put(new JSONObject().put("role", role).put("content", h.getString("t")));
            }
            if (msgs.length() > 0 && msgs.getJSONObject(msgs.length() - 1).getString("role").equals("user"))
                msgs.remove(msgs.length() - 1);
            msgs.put(new JSONObject().put("role", "user").put("content", msg));
            JSONObject body = new JSONObject()
                    .put("model", p.getString("claudemodel", "claude-sonnet-5-5"))
                    .put("max_tokens", 600)
                    .put("system", system)
                    .put("messages", msgs);
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes("UTF-8")); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.toString());
            if (code >= 400) return "Claude error: " + j.optJSONObject("error").optString("message", "HTTP " + code);
            JSONArray content = j.getJSONArray("content");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject part = content.getJSONObject(i);
                if ("text".equals(part.optString("type"))) out.append(part.getString("text"));
            }
            return out.toString().trim();
        } catch (Exception e) {
            return "Gagal menghubungi Claude: " + e.getMessage() + "\n\nKetik \"cek sekarang\" untuk analisa tanpa Claude.";
        }
    }
}
