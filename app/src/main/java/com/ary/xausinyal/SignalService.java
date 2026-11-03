package com.ary.xausinyal;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

/** Layanan latar depan: cek pasar setiap candle 5 menit selesai. */
public class SignalService extends Service {
    static final int NOTIF_ID = 1;
    HandlerThread thread;
    Handler handler;
    PowerManager.WakeLock wake;
    public static volatile boolean running = false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Runner.ensureChannels(this);
        Notification n = status("Memantau XAUUSD setiap 5 menit…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_ID, n);
        if (!running) {
            running = true;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xausinyal:pantau");
            wake.acquire();
            thread = new HandlerThread("pantau");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(tick);
        }
        return START_STICKY;
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            String s;
            try { s = Runner.runOnce(SignalService.this, true, false); }
            catch (Exception e) { s = "Error: " + e.getMessage(); }
            String first = s.split("\n").length > 1 ? s.split("\n")[1] : s;
            try {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.notify(NOTIF_ID, status(first));
            } catch (Exception ignored) { }
            // jadwal berikutnya: 20 detik setelah candle 5m berikutnya selesai
            long now = System.currentTimeMillis();
            long next = (now / 300000 + 1) * 300000 + 20000;
            handler.postDelayed(this, Math.max(30000, next - now));
        }
    };

    Notification status(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, Runner.CH_SERVICE) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("XAU Sinyal aktif")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
}
