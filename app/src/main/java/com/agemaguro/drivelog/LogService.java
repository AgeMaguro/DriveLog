package com.agemaguro.drivelog;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Keeps the logging engine alive with the app closed and the screen off. It shows an ongoing notification (Android
 * requires one), holds a wake lock while the truck is connected, and wakes itself every minute while it is waiting
 * for the adapter to show up. It stops itself when there is nothing left to wait for.
 */
public class LogService extends Service {
    private static final String TAG = "DriveLog";
    static final String ACTION_STOP = "com.agemaguro.drivelog.STOP";
    static final String ACTION_MARK = "com.agemaguro.drivelog.MARK";
    static final String ACTION_PING = "com.agemaguro.drivelog.PING";
    private static final String CHANNEL = "drivelog_logging";
    private static final int NOTE_ID = 7;
    private static final long WAIT_PING_MS = 60_000L;

    /** True while the service exists (the Activity checks this when it closes). */
    static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private String lastText = "";
    private boolean foreground;
    private boolean fullHold;
    private long lastCmd;
    private final Runnable tick = new Runnable() {
        @Override public void run() { doTick(); handler.postDelayed(this, 2000); }
    };

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, LogService.class));
        } catch (Exception e) {   // e.g. the system does not allow starting it from the background right now
            Log.w(TAG, "Could not start the logging service: " + e);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Background logging", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while DriveLog is connected to or waiting for your OBD2 adapter");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastCmd = SystemClock.elapsedRealtime();
        EngineHost h = EngineHost.get(this);
        // Android wants startForeground() quickly after startForegroundService(), so do it first.
        if (!foreground) {
            try {
                Notification n = build(h.engine.summary());
                lastText = h.engine.summary();
                startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                foreground = true;
            } catch (Exception e) {
                Log.w(TAG, "startForeground failed: " + e);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        h.ensureStarted();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            new Thread(() -> { h.engine.disconnectRequest(); handler.postDelayed(this::doTick, 600); }, "drivelog-stop").start();
        } else if (ACTION_MARK.equals(action)) {
            h.engine.markRequest();
        } else if (ACTION_PING.equals(action)) {
            // The alarm woke the phone: stay awake long enough for a connection attempt.
            ensureWake(30_000L);
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private void doTick() {
        EngineHost h = EngineHost.get(this);
        Engine e = h.engine;
        boolean fresh = SystemClock.elapsedRealtime() - lastCmd < 5000;   // give a just-requested connection time to show up
        if (!e.config().bg || (!e.wantsService() && !fresh)) { stopSelf(); return; }
        String text = e.summary();
        if (!text.equals(lastText)) {
            lastText = text;
            getSystemService(NotificationManager.class).notify(NOTE_ID, build(text));
        }
        boolean busy = e.isConnected() || e.isRecording();
        if (busy) { ensureWake(0); cancelPing(); }
        else { releaseWake(); schedulePing(); }
    }

    private Notification build(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("DriveLog")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                ;
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Mark", action(ACTION_MARK, 1)).build());
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Stop", action(ACTION_STOP, 2)).build());
        return b.build();
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, LogService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ---------------------------------------------------------------- power

    private void ensureWake(long timeoutMs) {
        if (wake == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriveLog:logging");
            wake.setReferenceCounted(false);
        }
        if (timeoutMs > 0) { if (!fullHold) wake.acquire(timeoutMs); }
        else if (!fullHold) { wake.acquire(); fullHold = true; }
    }

    private void releaseWake() {
        // a timed hold from a ping is left to expire by itself
        if (fullHold && wake != null) {
            try { wake.release(); } catch (Exception ignored) { }
            fullHold = false;
        }
    }

    private PendingIntent pingIntent() {
        Intent i = new Intent(this, LogService.class).setAction(ACTION_PING);
        return PendingIntent.getService(this, 3, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void schedulePing() {
        try {
            AlarmManager am = getSystemService(AlarmManager.class);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + WAIT_PING_MS, pingIntent());
        } catch (Exception e) { Log.w(TAG, "schedulePing: " + e); }
    }

    private void cancelPing() {
        try { getSystemService(AlarmManager.class).cancel(pingIntent()); } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        cancelPing();
        if (wake != null && wake.isHeld()) { try { wake.release(); } catch (Exception ignored) { } }
        EngineHost h = EngineHost.get(this);
        // With no screen open there is nobody left to use the engine: finish and save any recording.
        if (!h.activityAlive) new Thread(h.engine::shutdown, "drivelog-shutdown").start();
        super.onDestroy();
    }
}
