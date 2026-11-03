package com.ary.xausinyal;

import com.ary.xausinyal.engine.Analyzer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Ambil data harga (Twelve Data) dan order book (FastBull). */
public class DataClient {

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "XAUSinyal/1.0 (Android)");
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        if (code >= 400) throw new Exception("HTTP " + code + ": " + sb.substring(0, Math.min(200, sb.length())));
        return sb.toString();
    }

    /**
     * Candle dari Twelve Data, urut lama -> baru, hanya candle yang SUDAH close.
     * @param offset selisih harga broker, ditambahkan ke semua harga
     */
    public static List<Analyzer.Candle> candles(String apiKey, String symbol, String interval, int size,
                                                int intervalSec, double offset) throws Exception {
        String url = "https://api.twelvedata.com/time_series?symbol=" + URLEncoder.encode(symbol, "UTF-8")
                + "&interval=" + interval + "&outputsize=" + size + "&timezone=UTC&apikey=" + URLEncoder.encode(apiKey, "UTF-8");
        JSONObject j = new JSONObject(get(url));
        if (!"ok".equals(j.optString("status"))) {
            throw new Exception("Twelve Data: " + j.optString("message", "gagal ambil data"));
        }
        JSONArray v = j.getJSONArray("values");
        SimpleDateFormat f = new SimpleDateFormat(interval.endsWith("day") ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis() / 1000;
        List<Analyzer.Candle> out = new ArrayList<>();
        for (int i = 0; i < v.length(); i++) {
            JSONObject o = v.getJSONObject(i);
            long t = f.parse(o.getString("datetime")).getTime() / 1000;
            if (t + intervalSec > now + 5) continue; // candle masih berjalan
            out.add(new Analyzer.Candle(t,
                    o.getDouble("open") + offset, o.getDouble("high") + offset,
                    o.getDouble("low") + offset, o.getDouble("close") + offset));
        }
        Collections.reverse(out);
        return out;
    }

    /** Order book XAUUSD FastBull (diperbarui situsnya tiap 30 menit). */
    public static Analyzer.OrderBook orderBook(double offset) throws Exception {
        String url = "https://api.fastbull.com/fastbull-macro-data-service/api/getWebPendingOrder?orderType=0&pairId=28&selectTime=";
        JSONObject j = new JSONObject(get(url));
        JSONArray arr = new JSONArray(j.getString("bodyMessage"));
        Analyzer.OrderBook ob = new Analyzer.OrderBook();
        for (int k = 0; k < arr.length(); k++) {
            JSONObject d = arr.getJSONObject(k);
            JSONArray p = d.getJSONArray("price"), b = d.getJSONArray("buyOrder"), s = d.getJSONArray("sellOrder");
            int n = p.length();
            double[] pp = new double[n], bb = new double[n], ss = new double[n];
            double sb = 0, sss = 0;
            for (int i = 0; i < n; i++) {
                pp[i] = p.getDouble(i) + offset;
                bb[i] = Math.abs(b.getDouble(i));
                ss[i] = Math.abs(s.getDouble(i));
                sb += bb[i]; sss += ss[i];
            }
            if (d.optInt("type") == 1) { ob.price = pp; ob.ordBuy = bb; ob.ordSell = ss; }
            else if (d.optInt("type") == 2) {
                ob.posPrice = pp; ob.posBuy = bb; ob.posSell = ss;
                ob.longPct = sb + sss > 0 ? 100 * sb / (sb + sss) : 50;
            }
        }
        if (ob.price == null) throw new Exception("Format order book berubah");
        return ob;
    }
}
