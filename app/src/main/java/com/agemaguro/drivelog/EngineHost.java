package com.agemaguro.drivelog;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayDeque;

/**
 * Owns the one Engine of this app process and connects it to Android: Bluetooth transport, the web page (while it is
 * on screen) and the background service. The Activity and the Service both talk to the same instance.
 */
final class EngineHost implements Engine.Host {
    private static final String TAG = "DriveLog";
    static final String PREFS = "drivelog_cfg";

    /** The web page, while it is visible. */
    interface Ui { void push(String jsCall); }

    private static EngineHost instance;

    static synchronized EngineHost get(Context c) {
        if (instance == null) instance = new EngineHost(c.getApplicationContext());
        return instance;
    }

    final Context app;
    final Engine engine;
    private volatile Ui ui;
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    /** The Activity exists (so the engine must not be shut down just because the service stopped). */
    volatile boolean activityAlive;

    private EngineHost(Context app) {
        this.app = app;
        String version = "?";
        try { version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        engine = new Engine(new FileStore(new java.io.File(app.getFilesDir(), "drives")), this, version);
        engine.setConfig(savedConfig());
    }

    // ---------------------------------------------------------------- settings

    String savedConfig() {
        String s = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("cfg", null);
        return s == null ? "{}" : s;
    }

    void setConfig(String json) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("cfg", json).apply();
        engine.setConfig(json);
    }

    boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    void ensureStarted() { engine.start(); }

    /** Starts the foreground service when it should be running (idempotent). Needs the app to be allowed to start it. */
    void syncService() {
        Config c = engine.config();
        if (!engine.isRunning() || !c.bg || c.demo() || !hasBtPermission()) return;
        if (engine.wantsService()) LogService.start(app);
    }

    /** True if the saved settings say logging should happen by itself (used at boot, before any UI exists). */
    boolean savedWantsService() {
        Config c = Config.parse(savedConfig());
        return c.bg && c.autoConn && !c.demo() && !c.device.isEmpty() && hasBtPermission();
    }

    // ---------------------------------------------------------------- UI attach

    void attach(Ui u) { ui = u; }

    void detach(Ui u) { if (ui == u) ui = null; }

    private void push(String call) {
        Ui u = ui;
        if (u != null) u.push(call);
    }

    String logText() {
        synchronized (logs) {
            StringBuilder b = new StringBuilder();
            for (String l : logs) b.append(l).append('\n');
            return b.toString();
        }
    }

    // ---------------------------------------------------------------- Engine.Host

    @Override public Transport newTransport(Config cfg) { return cfg.demo() ? new DemoTransport() : new BtTransport(app); }

    @Override public void onState(String json) { push("DLNative.onEngineState(" + json + ")"); }

    @Override public void onSample(String json) { push("DLNative.onSample(" + json + ")"); }

    @Override public void onEvent(String json) { push("DLNative.onEvent(" + json + ")"); }

    @Override public void onNotice(String kind, String text) { push("DLNative.onNotice(" + JSONObject.quote(kind) + "," + JSONObject.quote(text) + ")"); }

    @Override public void onQuery(int id, String jsonArray) { push("DLNative.onQuery(" + id + "," + jsonArray + ")"); }

    @Override public boolean uiAttached() { return ui != null; }

    @Override
    public void log(String line) {
        Log.d(TAG, line);
        synchronized (logs) {
            logs.addLast(line);
            while (logs.size() > 400) logs.removeFirst();
        }
    }
}
